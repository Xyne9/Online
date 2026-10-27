package com.oj.problem.service.impl;

import com.oj.common.api.dto.problem.ProblemEditDTO;
import com.oj.common.api.dto.problem.ProblemJudgeConfigDTO;
import com.oj.common.api.dto.problem.TestCaseDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.web.context.UserContext;
import com.oj.problem.constant.ProblemDict;
import com.oj.problem.dto.ProblemQuery;
import com.oj.problem.entity.ProbKnowledgePoint;
import com.oj.problem.entity.ProbQuestion;
import com.oj.problem.entity.ProbTestCase;
import com.oj.problem.mapper.JudgeSubmissionReadMapper;
import com.oj.problem.mapper.ProbKnowledgePointMapper;
import com.oj.problem.mapper.ProbQuestionMapper;
import com.oj.problem.mapper.ProbTestCaseMapper;
import com.oj.problem.mapper.params.ProblemListParams;
import com.oj.problem.mapper.row.MyRecordRow;
import com.oj.problem.mapper.row.ProblemListRow;
import com.oj.problem.service.ProblemQueryService;
import com.oj.problem.support.JsonSupport;
import com.oj.problem.support.MarkdownSections;
import com.oj.problem.support.Times;
import com.oj.problem.vo.ProblemDetailVO;
import com.oj.problem.vo.ProblemListItemVO;
import com.oj.problem.vo.ProblemListVO;
import com.oj.problem.vo.ProblemStatsVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 题库只读查询实现。
 *
 * <p><b>权限口径（三条，全站唯一实现处）：</b></p>
 * <ol>
 *   <li><b>学生（含未登录）强制 {@code state=1}</b>：传了 {@code state} 也按 1 处理。
 *       未登录按「未登录学生」处理而不是抛 401——直接 curl 8082 调试时没有网关下发的
 *       {@code X-User-Id} 头，此时抛 401 会让本地联调完全没法验。</li>
 *   <li><b>教师 / 管理员</b>可见全部三态（0 草稿 / 1 已发布 / 2 已归档），
 *       {@code state} 不传就是「全部」。</li>
 *   <li><b>作答视角详情</b>：学生访问 {@code state != 1} 的题返回 <b>403</b>
 *       （契约 §3.3 错误码），不是 404——因为题目确实存在，只是无权看。
 *       软删或不存在才 404。</li>
 * </ol>
 *
 * <p><b>统计口径：</b>列表行的 {@code triedCount} 取 {@code prob_question.submit_count}，
 * 因为它是全站累计值（"1841 人尝试"），而 {@code judge_submission} 当前只有 1 行种子数据，
 * 用后者会让所有题的"尝试人数"都是 0。{@code acRate} 则由
 * {@code pass_count / submit_count} 现算，不额外落库。</p>
 *
 * @author oj-cloud
 */
@Service
public class ProblemQueryServiceImpl implements ProblemQueryService {

    private static final Logger log = LoggerFactory.getLogger(ProblemQueryServiceImpl.class);

    /** 分页默认值（契约 §3.2：page 默认 1、pageSize 默认 20，上限 100） */
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** kw 长度上限（契约 §3.2：≤50 字符） */
    private static final int MAX_KEYWORD_LENGTH = 50;

    /** 未登录时给 SQL 用的哨兵用户 ID：一个绝不存在的负数，语义 = "没有我的记录" */
    private static final long ANON_USER_ID = -1L;

    /** 「我的状态」筛选白名单 */
    private static final Set<String> MY_STATUS_FILTERS =
            new LinkedHashSet<>(List.of("all", "todo", "done", "fail"));

    /**
     * sortBy 白名单 → 实际 SQL 排序表达式。
     * <p>这是全站唯一允许拼进 SQL 的动态片段来源，用户输入永远只能当 key 来查表。</p>
     */
    private static final Map<String, String> SORT_COLUMNS = new LinkedHashMap<>();

    static {
        SORT_COLUMNS.put("id", "q.id");
        SORT_COLUMNS.put("lvl", "q.difficulty");
        // rate = 全站 AC 率，按「通过数 / 提交数」现算；分母为 0 的题按 0 处理
        SORT_COLUMNS.put("rate", "CASE WHEN q.submit_count > 0 "
                + "THEN q.pass_count / q.submit_count ELSE 0 END");
        // score = 我的最高分（未提交视为 0，保证未做过的题排在最低）
        SORT_COLUMNS.put("score", "COALESCE(my.myBestScore, 0)");
        SORT_COLUMNS.put("updatedAt", "q.update_time");
    }

    private final ProbQuestionMapper questionMapper;
    private final ProbTestCaseMapper testCaseMapper;
    private final ProbKnowledgePointMapper knowledgePointMapper;
    private final JudgeSubmissionReadMapper submissionReadMapper;

    public ProblemQueryServiceImpl(ProbQuestionMapper questionMapper,
                                   ProbTestCaseMapper testCaseMapper,
                                   ProbKnowledgePointMapper knowledgePointMapper,
                                   JudgeSubmissionReadMapper submissionReadMapper) {
        this.questionMapper = questionMapper;
        this.testCaseMapper = testCaseMapper;
        this.knowledgePointMapper = knowledgePointMapper;
        this.submissionReadMapper = submissionReadMapper;
    }

    // ==================================================================
    // 契约 #6：GET /api/problem/questions
    // ==================================================================

    @Override
    public ProblemListVO listQuestions(ProblemQuery query) {
        ProblemQuery q = query == null ? new ProblemQuery() : query;

        int page = q.getPage() == null ? DEFAULT_PAGE : Math.max(q.getPage(), 1);
        int pageSize = q.getPageSize() == null ? DEFAULT_PAGE_SIZE : q.getPageSize();
        pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        Long userId = UserContext.userIdOrNull();
        boolean teacherOrAbove = UserContext.isTeacherOrAbove();

        // ① 状态：学生（含未登录）强制 1；教师/管理员按传入值，不传 = 全部
        Integer state = teacherOrAbove ? q.getState() : ProblemDict.STATE_PUBLISHED;
        if (state != null && (state < ProblemDict.STATE_DRAFT || state > ProblemDict.STATE_ARCHIVED)) {
            throw BusinessException.badRequest("state 只能是 0（草稿）/ 1（已发布）/ 2（已归档）");
        }

        // ② 难度多选：逗号分隔，逐个校验 1~5
        List<Integer> levels = parseLevels(q.getLvl());

        // ③ 关键词：≤50 字符（超了直接 400，而不是静默截断——静默截断会让用户以为搜到了）
        String keyword = trimToNull(q.getKw());
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw BusinessException.badRequest("kw 最多 " + MAX_KEYWORD_LENGTH + " 个字符");
        }

        // ④ 我的状态筛选：白名单校验，学生之外的视角一律按 all 处理
        String myStatus = parseMyStatusFilter(q.getStatus(), userId);

        // ⑤ 排序：白名单 + 方向白名单
        String sortBy = trimToNull(q.getSortBy());
        String orderBy = SORT_COLUMNS.get(sortBy == null ? "id" : sortBy);
        if (orderBy == null) {
            throw BusinessException.badRequest("sortBy 只支持 " + String.join("/", SORT_COLUMNS.keySet()));
        }
        String orderDirection = parseSortOrder(q.getSortOrder());

        // ⑥ 组装 Mapper 参数
        ProblemListParams params = new ProblemListParams();
        params.setUserId(userId == null ? ANON_USER_ID : userId);
        params.setState(state);
        params.setLevels(levels);
        params.setKeyword(keyword);
        params.setTag(trimToNull(q.getTag()));
        params.setMyStatus(myStatus);
        params.setOnlyTodo("todo".equals(myStatus));
        params.setOnlyDone("done".equals(myStatus));
        params.setOnlyFail("fail".equals(myStatus));
        params.setOrderBy(orderBy);
        params.setOrderDirection(orderDirection);
        params.setOffset((long) (page - 1) * pageSize);
        params.setLimit(pageSize);
        params.setWeekStart(Times.nowInDbZone().minusDays(7));

        long total = questionMapper.countProblems(params);
        List<ProblemListRow> rows = total == 0
                ? Collections.emptyList()
                : questionMapper.selectProblemPage(params);

        // ⑦ 统计用「去掉我的状态筛选」的参数副本：进度面板是固定分母，
        //    否则用户点一下「未通过」，solved 会瞬间变 0，看起来像数据丢了
        ProblemListParams statsParams = copyForStats(params, myStatus);
        ProblemStatsVO stats = buildStats(questionMapper.selectStats(statsParams));

        List<ProblemListItemVO> list = new ArrayList<>(rows.size());
        // 首页推荐题：全局唯一（提交数最高的已发布题），翻页不变
        Long featuredId = questionMapper.selectFeaturedProblemId();
        for (ProblemListRow row : rows) {
            list.add(toListItem(row, featuredId));
        }
        return new ProblemListVO(list, total, page, pageSize, stats);
    }

    // ==================================================================
    // 契约 #7：GET /api/problem/questions/{id}
    // ==================================================================

    @Override
    public ProblemDetailVO getQuestionDetail(Long id) {
        if (id == null || id <= 0) {
            throw BusinessException.badRequest("题目 ID 不合法");
        }
        ProbQuestion question = questionMapper.selectByIdNotDeleted(id);
        if (question == null) {
            throw BusinessException.notFound("题目不存在或已删除");
        }

        // 学生（含未登录）只能看已发布；教师/管理员可看全部三态
        boolean teacherOrAbove = UserContext.isTeacherOrAbove();
        if (!teacherOrAbove && !Integer.valueOf(ProblemDict.STATE_PUBLISHED).equals(question.getState())) {
            throw BusinessException.forbidden("该题目尚未发布，暂时无法作答");
        }

        ProblemDetailVO vo = new ProblemDetailVO();
        vo.setId(question.getId());
        vo.setDisplayNo(displayNo(question.getId()));
        vo.setTitle(question.getTitle());
        vo.setDifficulty(question.getDifficulty());
        vo.setDifficultyLabel(ProblemDict.difficultyLabel(question.getDifficulty()));
        vo.setTags(JsonSupport.toStringList(question.getTags()));
        vo.setState(question.getState());

        // 题面：原文 + 抽出来的输入/输出小节（前端 s-workbench 分块渲染用）
        vo.setStatement(question.getContent());
        vo.setInputSpec(MarkdownSections.extractInputSpec(question.getContent()));
        vo.setOutputSpec(MarkdownSections.extractOutputSpec(question.getContent()));

        // 样例：优先取 sample_cases（导入脚本写入的权威样例），
        // 为空时回落到 is_public=1 的测试点——绝不读隐藏点的正文
        vo.setSamples(loadSamples(question));
        vo.setCodeTemplates(JsonSupport.toStringMap(question.getCodeTemplates()));

        vo.setTimeLimitMs(question.getTimeLimit());
        vo.setMemoryLimitMb(question.getMemoryLimit());
        vo.setLanguageMultipliers(buildLanguageOptions());

        int testCaseCount = question.getTestCaseCount() == null ? 0 : question.getTestCaseCount();
        vo.setTestCaseCount(testCaseCount);
        vo.setPublicCaseCount(testCaseMapper.countPublicByProblemId(question.getId()));

        vo.setScoringMode(ProblemDict.SCORING_MODE_OI);
        vo.setScoreRule(ProblemDict.SCORE_RULE);

        // 我的记录：未登录直接给「未提交」空壳，不发 SQL
        Long userId = UserContext.userIdOrNull();
        vo.setMyRecord(buildMyRecord(userId, question.getId()));

        // 判分链骨架：只取 seqNo/kind/score 三列（Mapper 的 SQL 就只 SELECT 这三列）
        List<ProbTestCase> briefs = testCaseMapper.selectBriefByProblemId(question.getId());
        vo.setChainPreview(toChainPreview(briefs));

        return vo;
    }

    // ==================================================================
    // 内部接口：GET /inner/problem/judge-config/{problemId}
    // ==================================================================

    @Override
    public ProblemJudgeConfigDTO getJudgeConfig(Long problemId) {
        ProbQuestion question = requireQuestion(problemId);

        ProblemJudgeConfigDTO dto = new ProblemJudgeConfigDTO();
        dto.setProblemId(question.getId());
        dto.setDisplayNo(displayNo(question.getId()));
        dto.setTitle(question.getTitle());
        dto.setTimeLimitMs(question.getTimeLimit());
        dto.setMemoryLimitMb(question.getMemoryLimit());
        dto.setLanguages(new ArrayList<>(ProblemDict.LANGUAGES.stream()
                .map(ProblemDict.LanguageSpec::language).toList()));
        dto.setScoringMode(ProblemDict.SCORING_MODE_OI);
        dto.setPublished(Integer.valueOf(ProblemDict.STATE_PUBLISHED).equals(question.getState()));

        // 测试点摘要：只有 id/seqNo/kind/score —— 判题服务据此算分，
        // 正文走 /inner/problem/test-cases 单独拉（docs/day3 §7 第 1 条）
        List<ProbTestCase> briefs = testCaseMapper.selectBriefByProblemId(question.getId());
        List<ProblemJudgeConfigDTO.TestCaseBriefDTO> briefDtos = new ArrayList<>(briefs.size());
        for (ProbTestCase tc : briefs) {
            ProblemJudgeConfigDTO.TestCaseBriefDTO brief = new ProblemJudgeConfigDTO.TestCaseBriefDTO();
            brief.setId(tc.getId());
            brief.setSeqNo(tc.getSeqNo());
            brief.setKind(tc.getKind());
            brief.setScore(tc.getScore());
            briefDtos.add(brief);
        }
        dto.setTestCaseBriefs(briefDtos);
        return dto;
    }

    // ==================================================================
    // 内部接口：GET /inner/problem/edit/{problemId}
    // ==================================================================

    @Override
    public ProblemEditDTO getForEdit(Long problemId) {
        ProbQuestion question = requireQuestion(problemId);

        ProblemEditDTO dto = new ProblemEditDTO();
        dto.setId(question.getId());
        dto.setDisplayNo(displayNo(question.getId()));
        dto.setTitle(question.getTitle());
        dto.setDifficulty(question.getDifficulty());
        dto.setKnowledgePointId(question.getKnowledgePointId());
        dto.setKnowledgePoint(knowledgePointName(question.getKnowledgePointId()));
        dto.setTags(String.join(",", JsonSupport.toStringList(question.getTags())));
        dto.setTimeLimitMs(question.getTimeLimit());
        dto.setMemoryLimitMb(question.getMemoryLimit());
        dto.setAiEnabled(question.getAiEnabled());
        dto.setAiQuotaLimit(question.getAiQuotaLimit());
        dto.setAiStages(JsonSupport.toIntList(question.getAiStages()));
        dto.setStatement(question.getContent());
        // 编辑态才允许带官方题解（AI 服务取它做参考答案注入）
        dto.setOfficialSolution(question.getOfficialSolution());
        dto.setState(question.getState());
        dto.setVersion(question.getVersion());
        dto.setSavedAt(Times.toEpochMs(question.getUpdateTime()));

        List<ProbTestCase> cases = testCaseMapper.selectByProblemId(question.getId());
        List<TestCaseDTO> caseDtos = new ArrayList<>(cases.size());
        int totalScore = 0;
        for (ProbTestCase tc : cases) {
            caseDtos.add(toTestCaseDto(tc));
            totalScore += tc.getScore() == null ? 0 : tc.getScore();
        }
        dto.setTestCases(caseDtos);
        dto.setTotalScore(totalScore);

        // 发布条件（契约 §3.4：totalScore === 100 && title 非空，由服务端算，前端只展示）
        boolean canPublish = totalScore == 100
                && question.getTitle() != null && !question.getTitle().isBlank();
        dto.setCanPublish(canPublish);
        dto.setPublishHint(canPublish ? null : publishHint(totalScore, question.getTitle()));
        return dto;
    }

    // ==================================================================
    // 内部接口：GET /inner/problem/test-cases/{problemId}
    // ==================================================================

    @Override
    public List<TestCaseDTO> listTestCases(Long problemId) {
        // 先确认题目存在，避免"题目不存在"被误当成"题目没有测试点"返回空列表
        requireQuestion(problemId);
        List<ProbTestCase> cases = testCaseMapper.selectByProblemId(problemId);
        List<TestCaseDTO> dtos = new ArrayList<>(cases.size());
        for (ProbTestCase tc : cases) {
            dtos.add(toTestCaseDto(tc));
        }
        return dtos;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 取题目，不存在或已软删抛 404。
     *
     * @param problemId 题目 ID
     * @return 题目实体（绝不为 null）
     */
    private ProbQuestion requireQuestion(Long problemId) {
        if (problemId == null || problemId <= 0) {
            throw BusinessException.badRequest("题目 ID 不合法");
        }
        ProbQuestion question = questionMapper.selectByIdNotDeleted(problemId);
        if (question == null) {
            throw BusinessException.notFound("题目不存在或已删除");
        }
        return question;
    }

    /**
     * 解析难度多选参数。
     *
     * @param raw 逗号分隔，如 "1,2,3"；null/空返回空列表（= 不限）
     * @return 去重后的难度列表
     * @throws BusinessException 400 出现非数字、0 或 >5
     */
    private List<Integer> parseLevels(String raw) {
        String text = trimToNull(raw);
        if (text == null) {
            return new ArrayList<>();
        }
        Set<Integer> levels = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw BusinessException.badRequest("lvl 只能是 1~5 的数字，逗号分隔");
            }
            if (level < 1 || level > 5) {
                throw BusinessException.badRequest("lvl 只能是 1~5 的数字，逗号分隔");
            }
            levels.add(level);
        }
        return new ArrayList<>(levels);
    }

    /**
     * 解析「我的状态」筛选。
     *
     * <p>未登录时没有"我的"可言，一律按 {@code all}（此时列表本来也不带 my* 字段）。</p>
     *
     * @param raw    原始值 all/todo/done/fail
     * @param userId 当前用户 ID，null = 未登录
     * @return 归一化后的筛选值
     */
    private String parseMyStatusFilter(String raw, Long userId) {
        String text = trimToNull(raw);
        if (text == null) {
            return "all";
        }
        String normalized = text.toLowerCase();
        if (!MY_STATUS_FILTERS.contains(normalized)) {
            throw BusinessException.badRequest("status 只能是 all/todo/done/fail");
        }
        return userId == null ? "all" : normalized;
    }

    /**
     * 解析排序方向。
     *
     * @param raw asc/desc，不区分大小写；null/空按契约默认 desc
     * @return "ASC" 或 "DESC"
     * @throws BusinessException 400 取值非法
     */
    private String parseSortOrder(String raw) {
        String text = trimToNull(raw);
        if (text == null) {
            return "DESC";
        }
        if ("asc".equalsIgnoreCase(text)) {
            return "ASC";
        }
        if ("desc".equalsIgnoreCase(text)) {
            return "DESC";
        }
        throw BusinessException.badRequest("sortOrder 只能是 asc 或 desc");
    }

    /** 复制一份参数用于统计（只把 myStatus 重置为 all，其余条件保持一致） */
    private ProblemListParams copyForStats(ProblemListParams src, String ignoredMyStatus) {
        ProblemListParams copy = new ProblemListParams();
        copy.setUserId(src.getUserId());
        copy.setState(src.getState());
        copy.setLevels(src.getLevels());
        copy.setKeyword(src.getKeyword());
        copy.setTag(src.getTag());
        copy.setMyStatus("all");
        copy.setOnlyTodo(false);
        copy.setOnlyDone(false);
        copy.setOnlyFail(false);
        copy.setOrderBy(src.getOrderBy());
        copy.setOrderDirection(src.getOrderDirection());
        copy.setOffset(src.getOffset());
        copy.setLimit(src.getLimit());
        copy.setWeekStart(src.getWeekStart());
        return copy;
    }

    /**
     * 组装四格统计。
     *
     * <p>MyBatis 返回的 Map 键名在不同驱动/配置下可能被改成大写，
     * 这里用「忽略大小写取值」的写法，避免 {@code stats.get("total")} 拿到 null。</p>
     *
     * @param row selectStats 的返回行
     * @return 统计视图（字段全部非 null）
     */
    private ProblemStatsVO buildStats(Map<String, Object> row) {
        ProblemStatsVO stats = new ProblemStatsVO();
        long total = longValue(row, "total");
        long solved = longValue(row, "solved");
        long tried = longValue(row, "tried");
        long weekNew = longValue(row, "weekNew");

        stats.setTotal(total);
        stats.setSolved(solved);
        stats.setTried(tried);
        stats.setWeekNew(weekNew);
        // AC 率 = 通过题数 / 尝试题数（不是通过次数 / 提交次数）：
        // 页面文案是"我的 AC 率"，口径是题目维度；分母 0 时给 0.0，绝不返回 NaN
        stats.setAcRate(tried <= 0 ? 0.0 : round1(solved * 100.0 / tried));
        return stats;
    }

    /** 从 Map 里按忽略大小写的键取值并转 long */
    private long longValue(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        Object value = row.get(key);
        if (value == null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 行数据 → 列表项。
     *
     * @param row        查询行
     * @param featuredId 首页推荐题 ID（可为 null）
     * @return 列表项
     */
    private ProblemListItemVO toListItem(ProblemListRow row, Long featuredId) {
        ProblemListItemVO item = new ProblemListItemVO();
        item.setId(row.getId());
        item.setDisplayNo(displayNo(row.getId()));
        item.setTitle(row.getTitle());
        item.setDifficulty(row.getDifficulty());
        item.setDifficultyLabel(ProblemDict.difficultyLabel(row.getDifficulty()));
        item.setTags(JsonSupport.toStringList(row.getTags()));

        // AC 率：pass_count / submit_count，保留一位小数；无提交时 0.0
        int submitCount = row.getSubmitCount() == null ? 0 : row.getSubmitCount();
        int passCount = row.getPassCount() == null ? 0 : row.getPassCount();
        item.setAcRate(submitCount <= 0 ? 0.0 : round1(passCount * 100.0 / submitCount));
        item.setTriedCount(submitCount);

        // 我的状态：null = 从未提交（契约 §2.1(1) 的注）
        Integer myStatus = row.getMyLastStatus();
        item.setMyStatus(myStatus);
        item.setMyStatusCode(ProblemDict.statusCode(myStatus));
        item.setMyStatusLabel(statusLabel(myStatus));
        item.setMyStatusFilter(myStatusFilterOf(myStatus, row.getMyBestScore()));
        item.setMyScore(row.getMyBestScore());

        item.setState(row.getState());
        item.setTimeLimitMs(row.getTimeLimit());
        item.setMemoryLimitMb(row.getMemoryLimit());
        item.setTestCaseCount(row.getTestCaseCount());
        item.setPublicCaseCount(row.getPublicCaseCount() == null ? 0 : row.getPublicCaseCount());
        item.setIsFeatured(featuredId != null && featuredId.equals(row.getId()));
        item.setUpdatedAt(Times.toEpochMs(row.getUpdateTime()));
        return item;
    }

    /**
     * 加载公开样例。
     *
     * <p>优先 {@code prob_question.sample_cases}（导入脚本写入的权威样例，含完整正文）；
     * 为空时回落到 {@code is_public = 1} 的测试点。<b>两条路都不会读隐藏点正文。</b></p>
     *
     * @param question 题目
     * @return 样例列表（可能为空）
     */
    private List<ProblemDetailVO.SampleVO> loadSamples(ProbQuestion question) {
        List<ProblemDetailVO.SampleVO> fromColumn =
                JsonSupport.toList(question.getSampleCases(), ProblemDetailVO.SampleVO.class);
        if (!fromColumn.isEmpty()) {
            return fromColumn;
        }
        List<ProblemDetailVO.SampleVO> result = new ArrayList<>();
        for (ProbTestCase tc : testCaseMapper.selectByProblemId(question.getId())) {
            if (Boolean.TRUE.equals(tc.getIsPublic())) {
                ProblemDetailVO.SampleVO sample = new ProblemDetailVO.SampleVO();
                sample.setInput(tc.getInputText());
                sample.setOutput(tc.getOutputText());
                result.add(sample);
            }
        }
        return result;
    }

    /**
     * 组装「我的记录」。
     *
     * @param userId    用户 ID，null = 未登录
     * @param problemId 题目 ID
     * @return 记录视图（未登录/未提交时 submitted=false，其余字段为 null）
     */
    private ProblemDetailVO.MyRecordVO buildMyRecord(Long userId, Long problemId) {
        ProblemDetailVO.MyRecordVO record = new ProblemDetailVO.MyRecordVO();
        record.setSubmitted(false);
        record.setAttempts(0);
        if (userId == null) {
            return record;
        }
        MyRecordRow row = submissionReadMapper.selectMyRecord(userId, problemId);
        if (row == null || row.getAttempts() == null || row.getAttempts() <= 0) {
            return record;
        }
        record.setSubmitted(true);
        record.setBestScore(row.getBestScore());
        record.setBestSubmissionId(row.getBestSubmissionId());
        record.setAttempts(row.getAttempts());
        record.setLastStatus(row.getLastStatus());
        record.setLastStatusCode(ProblemDict.statusCode(row.getLastStatus()));
        return record;
    }

    /**
     * 测试点摘要 → 判分链骨架。
     *
     * <p><b>只映射 seqNo/kind/score 三个字段</b>，目标类型
     * {@link ProblemDetailVO.ChainPreviewVO} 本身就没有输入输出字段，
     * 从类型层面杜绝泄题。</p>
     *
     * @param cases 摘要行
     * @return 判分链（按 seqNo 升序，Mapper 已排序）
     */
    private List<ProblemDetailVO.ChainPreviewVO> toChainPreview(List<ProbTestCase> cases) {
        List<ProblemDetailVO.ChainPreviewVO> preview = new ArrayList<>(cases.size());
        for (ProbTestCase tc : cases) {
            ProblemDetailVO.ChainPreviewVO node = new ProblemDetailVO.ChainPreviewVO();
            node.setSeqNo(tc.getSeqNo());
            node.setKind(tc.getKind());
            node.setScore(tc.getScore());
            preview.add(node);
        }
        return preview;
    }

    /**
     * 语言字典 → 详情响应里的 languageMultipliers。
     *
     * @return 语言选项列表（顺序固定 cpp/java/python）
     */
    private List<ProblemDetailVO.LanguageOptionVO> buildLanguageOptions() {
        List<ProblemDetailVO.LanguageOptionVO> options = new ArrayList<>(ProblemDict.LANGUAGES.size());
        for (ProblemDict.LanguageSpec spec : ProblemDict.LANGUAGES) {
            ProblemDetailVO.LanguageOptionVO option = new ProblemDetailVO.LanguageOptionVO();
            option.setLanguage(spec.language());
            option.setLabel(spec.label());
            option.setMultiplier(spec.multiplier());
            options.add(option);
        }
        return options;
    }

    /**
     * 测试点实体 → 共享 DTO（含正文，仅内部接口使用）。
     *
     * @param tc 测试点实体
     * @return TestCaseDTO
     */
    private TestCaseDTO toTestCaseDto(ProbTestCase tc) {
        TestCaseDTO dto = new TestCaseDTO();
        dto.setId(tc.getId());
        dto.setProblemId(tc.getProblemId());
        dto.setSeqNo(tc.getSeqNo());
        dto.setKind(tc.getKind());
        dto.setInput(tc.getInputText());
        dto.setOutput(tc.getOutputText());
        dto.setScore(tc.getScore());
        dto.setIsPublic(tc.getIsPublic());
        // tip 列在 DB 里不存在（见 DESC），保持 null，前端按可选字段处理
        dto.setTip(null);
        return dto;
    }

    /**
     * 知识点 ID → 名称。
     *
     * @param knowledgePointId 知识点 ID，可为 null
     * @return 名称；ID 为空或查不到时返回 null（前端显示「未分类」）
     */
    private String knowledgePointName(Long knowledgePointId) {
        if (knowledgePointId == null) {
            return null;
        }
        ProbKnowledgePoint point = knowledgePointMapper.selectById(knowledgePointId);
        return point == null ? null : point.getName();
    }

    /**
     * 发布条件不满足时的提示文案（契约 §3.4 的 {@code publishHint}）。
     *
     * @param totalScore 测试点总分
     * @param title      标题
     * @return 中文提示
     */
    private String publishHint(int totalScore, String title) {
        if (title == null || title.isBlank()) {
            return "标题不能为空";
        }
        return "测试点总分必须为 100，当前为 " + totalScore;
    }

    /** 展示号：直接由 id 拼 #（契约 §2.1(6)，题目不占 display_no 列） */
    private String displayNo(Long id) {
        return id == null ? null : "#" + id;
    }

    /** 判题状态 int → 中文标签（未提交返回 null） */
    private String statusLabel(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "排队中";
            case 1 -> "编译中";
            case 2 -> "评测中";
            case 3 -> "AC";
            case 4 -> "PAC";
            case 5 -> "WA";
            case 6 -> "TLE";
            case 7 -> "MLE";
            case 8 -> "RE";
            case 9 -> "CE";
            default -> null;
        };
    }

    /**
     * 判题状态 → 「我的状态」筛选档位（契约 02 §3.2 的 status 参数三档）。
     *
     * <p>口径与前端 s-problems.html:506-508 的筛选逻辑逐条对齐：
     * 未提交 = {@code todo}；有 AC 提交 = {@code done}；提交过但没 AC = {@code fail}。</p>
     *
     * <p><b>为什么先看分数再看状态：</b>一道题可能先 WA 后 AC，最近一次状态是 AC，
     * 但最高分才是判定「是否通过」的稳定依据。所以用 bestScore 是否满分
     * （或 lastStatus == AC）任一成立即算 done，避免"先 WA 后 AC 的题被算成 fail"。</p>
     *
     * @param lastStatus 最近一次判题状态，null = 未提交
     * @param bestScore  历史最高分，null = 未提交
     * @return todo / done / fail
     */
    private String myStatusFilterOf(Integer lastStatus, Integer bestScore) {
        if (lastStatus == null && bestScore == null) {
            return "todo";
        }
        if (Integer.valueOf(ProblemDict.JUDGE_STATUS_AC).equals(lastStatus)
                || (bestScore != null && bestScore >= 100)) {
            return "done";
        }
        return "fail";
    }

    /** 保留一位小数（契约 §3.2 备注：acRate 保留一位小数，0~100） */
    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** trim 后为空则返回 null（统一"空串当没传"的语义） */
    private String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
