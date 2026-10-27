package com.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.judge.entity.JudgeSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * {@code judge_submission} 数据访问。
 *
 * <p>通用 CRUD 走 {@link BaseMapper}；统计类查询（列表页四格指标 + 两张分布卡）
 * 用 {@code @Select} 写聚合 SQL —— 这些是列表接口一次要拿到的数据，
 * 拆成多次查询会让列表页在慢网络下出现「表格已渲染、统计还在转圈」。</p>
 *
 * <p><b>为什么不用 {@code QueryWrapper} 拼复杂查询：</b>
 * 判题列表的筛选条件（状态多选 / 语言 / 时间范围 / 关键词模糊题目名）
 * 里有关键词跨表（{@code prob_question.title}），Wrapper 拼不出来又不愿意引 XML，
 * 所以列表查询也放这里用注解 SQL 写，条件用 {@code <script>} 动态拼。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface JudgeSubmissionMapper extends BaseMapper<JudgeSubmission> {

    /**
     * 提交记录分页列表（学生视角：只看本人；教师视角：{@code userId=null} 时看全部）。
     *
     * @param userId    用户 ID（null = 不限）
     * @param statuses  状态白名单（null/空 = 不限）
     * @param language  语言（null/空 = 不限）
     * @param keyword   关键词（题目名或展示号模糊，null/空 = 不限）
     * @param rangeDays 近 N 天（null = 不限）
     * @param sortBy    排序字段（已由 service 白名单校验）
     * @param sortOrder asc / desc（已由 service 校验）
     * @param offset    偏移
     * @param limit     条数
     * @return 行（含题目名 problemTitle，来自 LEFT JOIN）
     */
    @Select("""
            <script>
            SELECT s.id, s.display_no AS displayNo, s.user_id AS userId, s.problem_id AS problemId,
                   q.title AS problemTitle, s.assignment_id AS assignmentId, s.language,
                   s.status, s.oi_score AS oiScore, s.pass_case_count AS passCaseCount,
                   s.total_case_count AS totalCaseCount, s.time_used AS timeUsed,
                   s.memory_used AS memoryUsed, s.first_failed_case_id AS firstFailedCaseId,
                   s.is_overdue AS isOverdue, s.create_time AS createTime, s.finished_at AS finishedAt
            FROM judge_submission s
            LEFT JOIN prob_question q ON q.id = s.problem_id
            <where>
                <if test="userId != null"> AND s.user_id = #{userId} </if>
                <if test="statuses != null and statuses.size() > 0">
                    AND s.status IN
                    <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
                </if>
                <if test="language != null and language != ''"> AND s.language = #{language} </if>
                <if test="keyword != null and keyword != ''">
                    AND (q.title LIKE CONCAT('%', #{keyword}, '%') OR s.display_no LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="rangeDays != null"> AND s.create_time &gt;= DATE_SUB(NOW(), INTERVAL #{rangeDays} DAY) </if>
            </where>
            ORDER BY
            <choose>
                <when test="sortBy == 'timeMs'"> s.time_used </when>
                <when test="sortBy == 'score'"> s.oi_score </when>
                <otherwise> s.id </otherwise>
            </choose>
            <choose>
                <when test="sortOrder == 'asc'"> ASC </when>
                <otherwise> DESC </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectSubmissionPage(@Param("userId") Long userId,
                                                   @Param("statuses") List<Integer> statuses,
                                                   @Param("language") String language,
                                                   @Param("keyword") String keyword,
                                                   @Param("rangeDays") Integer rangeDays,
                                                   @Param("sortBy") String sortBy,
                                                   @Param("sortOrder") String sortOrder,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    /**
     * 与 {@link #selectSubmissionPage} 同条件的总行数。
     *
     * @param userId    用户 ID（null = 不限）
     * @param statuses  状态白名单
     * @param language  语言
     * @param keyword   关键词
     * @param rangeDays 近 N 天
     * @return 总行数
     */
    @Select("""
            <script>
            SELECT COUNT(1)
            FROM judge_submission s
            LEFT JOIN prob_question q ON q.id = s.problem_id
            <where>
                <if test="userId != null"> AND s.user_id = #{userId} </if>
                <if test="statuses != null and statuses.size() > 0">
                    AND s.status IN
                    <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
                </if>
                <if test="language != null and language != ''"> AND s.language = #{language} </if>
                <if test="keyword != null and keyword != ''">
                    AND (q.title LIKE CONCAT('%', #{keyword}, '%') OR s.display_no LIKE CONCAT('%', #{keyword}, '%'))
                </if>
                <if test="rangeDays != null"> AND s.create_time &gt;= DATE_SUB(NOW(), INTERVAL #{rangeDays} DAY) </if>
            </where>
            </script>
            """)
    long countSubmissionPage(@Param("userId") Long userId,
                             @Param("statuses") List<Integer> statuses,
                             @Param("language") String language,
                             @Param("keyword") String keyword,
                             @Param("rangeDays") Integer rangeDays);

    /**
     * 状态分布（列表页「结果分布」卡）。
     *
     * @param userId 用户 ID（null = 不限）
     * @param days   近 N 天（null = 不限）
     * @return 每行 {@code {status, cnt}}
     */
    @Select("""
            <script>
            SELECT s.status AS status, COUNT(1) AS cnt
            FROM judge_submission s
            <where>
                <if test="userId != null"> AND s.user_id = #{userId} </if>
                <if test="days != null"> AND s.create_time &gt;= DATE_SUB(NOW(), INTERVAL #{days} DAY) </if>
            </where>
            GROUP BY s.status
            </script>
            """)
    List<Map<String, Object>> countStatusDistribution(@Param("userId") Long userId,
                                                     @Param("days") Integer days);

    /**
     * 语言分布（列表页「语言分布」卡）。
     *
     * @param userId 用户 ID（null = 不限）
     * @param days   近 N 天（null = 不限）
     * @return 每行 {@code {language, cnt}}
     */
    @Select("""
            <script>
            SELECT s.language AS language, COUNT(1) AS cnt
            FROM judge_submission s
            <where>
                <if test="userId != null"> AND s.user_id = #{userId} </if>
                <if test="days != null"> AND s.create_time &gt;= DATE_SUB(NOW(), INTERVAL #{days} DAY) </if>
            </where>
            GROUP BY s.language
            </script>
            """)
    List<Map<String, Object>> countLanguageDistribution(@Param("userId") Long userId,
                                                       @Param("days") Integer days);

    /**
     * 四格指标：总 AC 数、平均耗时、本周提交数、本周 AC 数、本周不同题目数。
     *
     * <p>一次查完，避免列表接口为了 5 个数字打 5 次库。</p>
     *
     * @param userId 用户 ID（null = 不限）
     * @return {@code {totalAc, avgTimeMs, weekCount, weekAc, weekProblemCount}}
     */
    @Select("""
            <script>
            SELECT
              SUM(CASE WHEN s.status = 3 THEN 1 ELSE 0 END) AS totalAc,
              ROUND(AVG(CASE WHEN s.status IN (3,4,5) THEN s.time_used END)) AS avgTimeMs,
              SUM(CASE WHEN s.create_time &gt;= DATE_SUB(NOW(), INTERVAL 7 DAY) THEN 1 ELSE 0 END) AS weekCount,
              SUM(CASE WHEN s.create_time &gt;= DATE_SUB(NOW(), INTERVAL 7 DAY) AND s.status = 3 THEN 1 ELSE 0 END) AS weekAc,
              COUNT(DISTINCT CASE WHEN s.create_time &gt;= DATE_SUB(NOW(), INTERVAL 7 DAY)
                                  THEN s.problem_id END) AS weekProblemCount
            FROM judge_submission s
            <where>
                <if test="userId != null"> AND s.user_id = #{userId} </if>
            </where>
            </script>
            """)
    Map<String, Object> selectOverviewStats(@Param("userId") Long userId);

    /**
     * 某用户某题的历史提交（时间正序，轨迹图用）。
     *
     * @param userId    用户 ID
     * @param problemId 题目 ID
     * @param limit     条数（取最近的 N 条，再在 service 里正序）
     * @return 行
     */
    @Select("""
            SELECT s.id AS id, s.display_no AS displayNo, s.status AS status, s.oi_score AS oiScore,
                   s.time_used AS timeUsed, s.memory_used AS memoryUsed, s.create_time AS createTime
            FROM judge_submission s
            WHERE s.user_id = #{userId} AND s.problem_id = #{problemId}
            ORDER BY s.id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectTraceRows(@Param("userId") Long userId,
                                              @Param("problemId") Long problemId,
                                              @Param("limit") int limit);

    /**
     * 取某用户某题的历史最高分提交 ID（并列取最早那条）。
     *
     * @param userId    用户 ID
     * @param problemId 题目 ID
     * @return 提交 ID；无记录返回 null
     */
    @Select("""
            SELECT s.id FROM judge_submission s
            WHERE s.user_id = #{userId} AND s.problem_id = #{problemId}
            ORDER BY s.oi_score DESC, s.id ASC
            LIMIT 1
            """)
    Long selectBestSubmissionId(@Param("userId") Long userId, @Param("problemId") Long problemId);

    /**
     * 最近一次<b>失败</b>的提交（AI 阶段③ 的失败诊断数据源）。
     *
     * @param userId       用户 ID
     * @param problemId    题目 ID
     * @param assignmentId 作业 ID（null / 0 = 不限）
     * @return 提交记录；无记录返回 null
     */
    @Select("""
            <script>
            SELECT s.* FROM judge_submission s
            WHERE s.user_id = #{userId} AND s.problem_id = #{problemId}
              AND s.status IN (4, 5, 6, 7, 8, 9)
            AND s.assignment_id = COALESCE(#{assignmentId}, 0)
            ORDER BY s.id DESC
            LIMIT 1
            </script>
            """)
    JudgeSubmission selectLatestFailed(@Param("userId") Long userId,
                                       @Param("problemId") Long problemId,
                                       @Param("assignmentId") Long assignmentId);

    /**
     * 该用户该题的历史失败次数（AI 判断是否「反复踩同一个坑」）。
     *
     * @param userId    用户 ID
     * @param problemId 题目 ID
     * @return 次数
     */
    @Select("""
            SELECT COUNT(1) FROM judge_submission
            WHERE user_id = #{userId} AND problem_id = #{problemId} AND status IN (4, 5, 6, 7, 8, 9)
            """)
    int countFailed(@Param("userId") Long userId, @Param("problemId") Long problemId);

    /**
     * 批量取最佳提交的代码（查重任务用）。
     *
     * <p>只查需要的列，<b>不带 case_details</b>（那是 JSON 大字段，
     * 50 人 × 5 题一次拉回来会很重）。</p>
     *
     * @param ids 提交 ID 列表（空列表时调用方应直接返回空）
     * @return 行
     */
    @Select("""
            <script>
            SELECT s.id AS id, s.user_id AS userId, s.problem_id AS problemId, s.language AS language,
                   s.oi_score AS oiScore, s.status AS status, s.code AS code,
                   CHAR_LENGTH(s.code) AS codeLength, s.create_time AS createTime
            FROM judge_submission s
            WHERE s.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectBestCodeRows(@Param("ids") List<Long> ids);

    /**
     * 提交记录增量计数：{@code prob_question.submit_count + 1}。
     *
     * <p><b>跨服务直连是本期妥协</b>（见 {@code ProbQuestionJudgeConfig} 类注释）：
     * 题目计数归题库服务所有，正确做法是判题服务通过 Feign 调
     * {@code ProblemInnerFeignClient} 让题库服务自己更新。本期为了少一条 RPC 链路，
     * 直接在判题服务的事务里 UPDATE 同库的 {@code prob_question}。
     * 题库服务一旦独立分库，这两条 SQL 必须改成 Feign 调用。</p>
     *
     * @param problemId 题目 ID
     * @return 影响行数
     */
    @Update("UPDATE prob_question SET submit_count = submit_count + 1 WHERE id = #{problemId}")
    int increaseSubmitCount(@Param("problemId") Long problemId);

    /**
     * 提交记录增量计数：AC 时 {@code prob_question.pass_count + 1}。
     *
     * @param problemId 题目 ID
     * @return 影响行数
     */
    @Update("UPDATE prob_question SET pass_count = pass_count + 1 WHERE id = #{problemId}")
    int increasePassCount(@Param("problemId") Long problemId);
}
