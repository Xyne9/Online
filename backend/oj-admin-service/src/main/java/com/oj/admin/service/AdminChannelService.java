package com.oj.admin.service;

import com.oj.admin.config.AdminProperties;
import com.oj.admin.dao.AdminChannelDao;
import com.oj.admin.support.ChannelProbeClient;
import com.oj.admin.support.SecretResolver;
import com.oj.common.api.dto.admin.ChannelActionDTO;
import com.oj.common.api.dto.admin.ChannelCommandDTO;
import com.oj.common.api.dto.admin.ChannelDetailDTO;
import com.oj.common.api.dto.admin.ChannelPageDTO;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.redis.RedisKeys;
import com.oj.common.core.result.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 模型通道簇（契约 §7.2 #72~#79）的业务实现。
 *
 * <p><b>三条硬约束</b>（写在这里，改动时对着看）：</p>
 * <ol>
 *   <li><b>任何对 {@code ai_model_config} / {@code ai_model_role} 的写操作后必须 DEL
 *       {@code oj:ai:config:channels}</b>（agent.md §2.6 记录过的坑）。不清缓存时 AI 服务
 *       读到的是旧快照：DB 里密钥已改成占位符、Redis 里还是空串，云端通道会在发请求
 *       <b>之前</b>就以「未配置密钥」静默降级，日志里连「密钥无效」都没有。
 *       本类所有写方法统一走 {@link #evictChannelCache()}。</li>
 *   <li><b>探活必须真发请求</b>，见 {@link ChannelProbeClient}。</li>
 *   <li><b>密钥永不出现在返回值 / 日志</b>：对外只给 {@link SecretResolver#mask(String)}。</li>
 * </ol>
 *
 * @author oj-cloud
 */
@Service
public class AdminChannelService {

    private static final Logger log = LoggerFactory.getLogger(AdminChannelService.class);

    /** 通道编码规则（契约 §7.2 备注：运维自定义稳定标识，与厂商型号解耦） */
    private static final String CODE_PATTERN = "^[a-z][a-z0-9-]{2,31}$";

    /** 合法角色（§8.6 DDL 注释：ROUTER 路由 / TUTOR 教学 / GUARDRAIL 护栏） */
    private static final Set<String> ROLES = Set.of("ROUTER", "TUTOR", "GUARDRAIL");

    /** 排序白名单：契约 §7.2.1 的 sortBy 三值，映射到内部枚举 */
    private static final Set<String> SORT_KEYS = Set.of("today", "name", "ttft");

    /** 状态筛选白名单 */
    private static final Set<String> STATE_FILTERS = Set.of("all", "on", "degrade", "off", "probing");

    private final AdminChannelDao dao;
    private final AdminProperties properties;
    private final SecretResolver secrets;
    private final ChannelProbeClient probeClient;
    private final StringRedisTemplate redis;

    public AdminChannelService(AdminChannelDao dao, AdminProperties properties,
                               SecretResolver secrets, ChannelProbeClient probeClient,
                               StringRedisTemplate redis) {
        this.dao = dao;
        this.properties = properties;
        this.secrets = secrets;
        this.probeClient = probeClient;
        this.redis = redis;
    }

    // ==================================================================
    // #72 列表
    // ==================================================================

    /**
     * 通道列表（含今日调用 / TTFT / 成本与四档汇总）。
     *
     * @param page      页码
     * @param pageSize  每页条数
     * @param state     健康态筛选（all/on/degrade/off/probing）
     * @param kw        关键词（编码 / 名称 / 厂商 / 型号）
     * @param sortBy    排序键（today/name/ttft）
     * @param sortOrder 排序方向（asc/desc）
     * @return 分页结果
     */
    public ChannelPageDTO list(int page, int pageSize, String state, String kw,
                               String sortBy, String sortOrder) {
        int safePage = PageResult.normalizePage(page);
        int safeSize = PageResult.normalizePageSize(pageSize);
        String filter = normalizeStateFilter(state);
        String sortKey = (sortBy == null || sortBy.isBlank()) ? "today" : sortBy.trim().toLowerCase(Locale.ROOT);
        if (!SORT_KEYS.contains(sortKey)) {
            throw BusinessException.badRequest("sortBy 只支持 today / name / ttft");
        }
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);

        List<Map<String, Object>> rows = dao.findAll();
        Map<String, List<String>> roles = dao.loadRoleBindings();
        Map<String, Map<String, Object>> todayAgg = dao.todayAggByChannel(todayStart());

        List<ChannelPageDTO.Item> all = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ChannelPageDTO.Item item = toItem(row, roles.get(str(row, "channel_code")),
                    todayAgg.get(str(row, "channel_code")));
            all.add(item);
        }

        ChannelPageDTO.Summary summary = buildSummary(all);

        List<ChannelPageDTO.Item> filtered = all.stream()
                .filter(i -> matchesState(i, filter))
                .filter(i -> matchesKw(i, kw))
                .sorted(comparator(sortKey, desc))
                .toList();

        int from = Math.min((safePage - 1) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());

        ChannelPageDTO out = new ChannelPageDTO();
        out.setList(new ArrayList<>(filtered.subList(from, to)));
        out.setTotal((long) filtered.size());
        out.setPage(safePage);
        out.setPageSize(safeSize);
        out.setHasNext((long) safePage * safeSize < filtered.size());
        out.setSummary(summary);
        return out;
    }

    // ==================================================================
    // #73 详情
    // ==================================================================

    /**
     * 通道详情（含 24h 趋势、近期调用、降级事件）。
     *
     * @param id 主键
     * @return 详情
     */
    public ChannelDetailDTO detail(Long id) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");
        LocalDateTime today = todayStart();
        Map<String, Object> agg = dao.todayAggByChannel(today).get(code);

        ChannelDetailDTO dto = new ChannelDetailDTO();
        dto.setId(lng(row, "id"));
        dto.setChannelCode(code);
        dto.setChannelName(str(row, "channel_name"));
        dto.setVendor(str(row, "vendor"));
        dto.setModelName(str(row, "model_name"));
        dto.setRoleBindings(dao.findRoles(code));
        dto.setBaseUrl(str(row, "base_url"));
        /*
          apiKeyMasked 只回脱敏形态。注意：DB 里通常是 ${OJ_ZHIPU_API_KEY} 这类占位符，
          mask() 对占位符固定回 "****"（回显变量名等于泄漏部署信息）。
        */
        dto.setApiKeyMasked(secrets.mask(str(row, "api_key")));
        dto.setTimeoutMs(secondsToMs(intOf(row, "timeout_seconds")));
        dto.setMaxTokens(intOf(row, "max_tokens"));
        dto.setTemperature(row.get("temperature") == null ? null : dbl(row, "temperature"));
        dto.setMaxConcurrency(intOf(row, "max_concurrency"));
        dto.setIsEnabled(bool(row, "is_enabled"));
        dto.setHealthStatus(str(row, "health_status"));
        dto.setHealthNote(str(row, "health_note"));
        dto.setAvgTtftMs(avgTtft(agg));

        dto.setTrend24h(trend24h(code));
        dto.setRecentCalls(recentCalls(code));
        dto.setDegradeEvents(degradeEvents());
        return dto;
    }

    // ==================================================================
    // #74 新增
    // ==================================================================

    /**
     * 新增通道（创建后自动探活一次）。
     *
     * <p><b>为什么先回 PROBING 再异步探活</b>：探活最坏要等一个超时周期（云端 40s），
     * 同步做会让「保存」按钮转圈 40 秒。契约 §7.2.3 的响应体本来就写了
     * {@code healthStatus:"PROBING"}，说明期望的就是异步完成。</p>
     *
     * @param dto 请求体
     * @return 创建结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChannelActionDTO.Created create(ChannelCommandDTO dto) {
        String code = requireCode(dto);
        if (dao.findByCode(code) != null) {
            throw BusinessException.badRequest("channelCode 已存在：" + code);
        }
        List<String> roles = normalizeRoles(dto.getRoleBindings());

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("channel_code", code);
        p.put("channel_name", requireText(dto.getChannelName(), "channelName"));
        p.put("vendor", requireText(dto.getVendor(), "vendor"));
        p.put("provider_type", providerTypeOf(dto.getVendor(), dto.getProviderType()));
        String baseUrl = requireText(dto.getBaseUrl(), "baseUrl");
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw BusinessException.badRequest("baseUrl 必须 http:// 或 https:// 开头");
        }
        p.put("base_url", baseUrl);
        p.put("api_key", dto.getApiKey() == null ? "" : dto.getApiKey().trim());
        p.put("model_name", requireText(dto.getModelName(), "modelName"));
        // v1.0 兼容列：本地通道是系统固定，云端是可降级的可选 Tutor
        p.put("role_binding", "OLLAMA".equals(p.get("provider_type")) ? "SYSTEM_FIXED" : "TUTOR_OPTIONAL");
        p.put("timeout_seconds", msToSeconds(dto.getTimeoutMs() == null ? 8000L : dto.getTimeoutMs()));
        p.put("max_tokens", clamp(dto.getMaxTokens(), 256, 32768, 2048));
        p.put("temperature", clampTemp(dto.getTemperature()));
        p.put("max_concurrency", clamp(dto.getMaxConcurrency(), 1, 64, 8));
        p.put("health_status", "PROBING");
        p.put("health_note", null);
        p.put("last_probe_at", null);
        p.put("is_enabled", dto.getIsEnabled() == null || dto.getIsEnabled());

        Long id = dao.insert(p);
        if (id == null) {
            throw BusinessException.internalError("通道创建失败：未取得主键");
        }
        dao.insertRoles(code, roles);
        evictChannelCache();

        String taskId = "probe-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // 事务提交后再真发请求：否则异步线程读到的可能是「未提交 / 已回滚」的行，
        // 表现为「探活成功但库里没有这条通道」。
        Long createdId = id;
        runAfterCommit(() -> CompletableFuture.runAsync(() -> probeAfterCommit(createdId, taskId)));

        ChannelActionDTO.Created out = new ChannelActionDTO.Created();
        out.setId(id);
        out.setChannelCode(code);
        out.setHealthStatus("PROBING");
        out.setFirstProbeTaskId(taskId);
        log.info("新增通道 id={} code={} roles={} 首次探活任务={}", id, code, roles, taskId);
        return out;
    }

    // ==================================================================
    // #75 更新
    // ==================================================================

    /**
     * 更新通道配置（{@code null} 字段 = 不改）。
     *
     * @param id  主键
     * @param dto 请求体
     * @return 更新结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChannelActionDTO.Updated update(Long id, ChannelCommandDTO dto) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");

        Map<String, Object> patch = new LinkedHashMap<>();
        if (dto.getChannelName() != null) {
            patch.put("channel_name", requireText(dto.getChannelName(), "channelName"));
        }
        if (dto.getVendor() != null) {
            patch.put("vendor", requireText(dto.getVendor(), "vendor"));
            if (dto.getProviderType() == null) {
                patch.put("provider_type", providerTypeOf(dto.getVendor(), null));
            }
        }
        if (dto.getProviderType() != null) {
            patch.put("provider_type", normalizeProviderType(dto.getProviderType()));
        }
        if (dto.getModelName() != null) {
            patch.put("model_name", requireText(dto.getModelName(), "modelName"));
        }
        if (dto.getBaseUrl() != null) {
            String baseUrl = requireText(dto.getBaseUrl(), "baseUrl");
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw BusinessException.badRequest("baseUrl 必须 http:// 或 https:// 开头");
            }
            patch.put("base_url", baseUrl);
        }
        // 空字符串表示「不动密钥」：前端编辑表单回显的是脱敏值，直接提交会把 **** 写回库里
        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            patch.put("api_key", dto.getApiKey().trim());
        }
        if (dto.getTimeoutMs() != null) {
            patch.put("timeout_seconds", msToSeconds(dto.getTimeoutMs()));
        }
        if (dto.getMaxTokens() != null) {
            patch.put("max_tokens", clamp(dto.getMaxTokens(), 256, 32768, 2048));
        }
        if (dto.getTemperature() != null) {
            patch.put("temperature", clampTemp(dto.getTemperature()));
        }
        if (dto.getMaxConcurrency() != null) {
            patch.put("max_concurrency", clamp(dto.getMaxConcurrency(), 1, 64, 8));
        }
        if (dto.getIsEnabled() != null) {
            patch.put("is_enabled", dto.getIsEnabled() ? 1 : 0);
        }
        // channelCode 有意不可改：它是缓存 Hash 的字段名、也是 ai_model_role 的外键，
        // 改它等于「删一条建一条」，会让历史 ai_chat_record.selected_channel 失去归属。
        if (dto.getChannelCode() != null && !dto.getChannelCode().isBlank()
                && !dto.getChannelCode().trim().equals(code)) {
            throw BusinessException.badRequest("channelCode 不可修改（它是缓存键与角色绑定外键）；如需改名请新建通道");
        }

        dao.update(id, patch);
        if (dto.getRoleBindings() != null) {
            List<String> roles = normalizeRoles(dto.getRoleBindings());
            dao.deleteRoles(code);
            dao.insertRoles(code, roles);
        }
        evictChannelCache();

        ChannelActionDTO.Updated out = new ChannelActionDTO.Updated();
        out.setId(id);
        out.setUpdatedAt(System.currentTimeMillis());
        log.info("更新通道 id={} code={} 变更列={}", id, code, patch.keySet());
        return out;
    }

    // ==================================================================
    // #76 删除
    // ==================================================================

    /**
     * 删除通道（有历史调用则转禁用）。
     *
     * <p><b>409 的判定</b>（契约 §7.2.5）：被 {@code roleBindings} 引用
     * **且没有其他可用通道**时不能删——否则 AI 服务找不到任何可用的 Router/Tutor，
     * 全站 AI 直接不可用。{@code force=true} 可越过该保护（运维明确知情时用）。</p>
     *
     * @param id    主键
     * @param force 是否强制删除
     * @return 删除结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChannelActionDTO.Deleted delete(Long id, boolean force) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");

        if (!force) {
            for (String role : dao.findRoles(code)) {
                if (dao.countAvailableByRole(role, code) == 0) {
                    throw BusinessException.conflict(
                            "通道 " + code + " 是角色 " + role + " 唯一可用通道，删除会导致该角色无通道可用；"
                                    + "请先启用其他通道，或使用 force=true 强制删除");
                }
            }
        }

        boolean softDeleted = dao.countCalls(code) > 0;
        if (softDeleted) {
            // 有历史调用：保留行以维持审计可追溯，转禁用 + OFF
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("is_enabled", 0);
            patch.put("health_status", "OFF");
            patch.put("health_note", "已删除（存在历史调用，保留行以维持审计可追溯）");
            dao.update(id, patch);
            dao.deleteRoles(code);
        } else {
            dao.deleteRoles(code);
            dao.deleteChannel(id);
        }
        evictChannelCache();

        ChannelActionDTO.Deleted out = new ChannelActionDTO.Deleted();
        out.setId(id);
        out.setDeleted(true);
        out.setSoftDeleted(softDeleted);
        log.info("删除通道 id={} code={} softDeleted={}", id, code, softDeleted);
        return out;
    }

    // ==================================================================
    // #77 启停 / 手动降级
    // ==================================================================

    /**
     * 启用 / 禁用 / 手动降级。
     *
     * @param id     主键
     * @param action enable / disable / degrade
     * @return 状态变更结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ChannelActionDTO.StateChanged changeState(Long id, String action) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> patch = new LinkedHashMap<>();

        // 注意（agent.md §0.1 事实 #6）：这里刻意不用三元表达式混 Integer 与 int，
        // 全部走 if/else + 显式包装，避免拆箱 NPE。
        int enabledFlag;
        String nextStatus;
        switch (act) {
            case "enable" -> {
                if (bool(row, "is_enabled") && "ON".equalsIgnoreCase(str(row, "health_status"))) {
                    throw BusinessException.conflict("通道 " + code + " 已处于启用且在线状态");
                }
                enabledFlag = 1;
                nextStatus = "ON";
                patch.put("health_note", null);
            }
            case "disable" -> {
                if (!bool(row, "is_enabled")) {
                    throw BusinessException.conflict("通道 " + code + " 已处于禁用状态");
                }
                enabledFlag = 0;
                nextStatus = "OFF";
                patch.put("health_note", "管理员手动禁用");
            }
            case "degrade" -> {
                if ("DEGRADE".equalsIgnoreCase(str(row, "health_status"))) {
                    throw BusinessException.conflict("通道 " + code + " 已处于降级状态");
                }
                // 手动降级不改 is_enabled：降级是「仍启用但暂不走它」，与「禁用」是两件事
                enabledFlag = bool(row, "is_enabled") ? 1 : 0;
                nextStatus = "DEGRADE";
                patch.put("health_note", "管理员手动降级");
            }
            default -> throw BusinessException.badRequest("action 只支持 enable / disable / degrade");
        }
        if (!"degrade".equals(act)) {
            patch.put("is_enabled", enabledFlag);
        }
        patch.put("health_status", nextStatus);
        dao.update(id, patch);
        evictChannelCache();

        ChannelActionDTO.StateChanged out = new ChannelActionDTO.StateChanged();
        out.setId(id);
        out.setIsEnabled(enabledFlag == 1);
        out.setHealthStatus(nextStatus);
        out.setEffectiveAt(System.currentTimeMillis());
        log.info("通道状态变更 id={} code={} action={} -> {}", id, code, act, nextStatus);
        return out;
    }

    // ==================================================================
    // #78 探活
    // ==================================================================

    /**
     * 连通性探测（真实发一次最小请求）。
     *
     * <p><b>防抖</b>：探活前抢 {@code oj:admin:probe:{channelCode}} 的 SETNX（TTL 10s）。
     * 抢不到说明 10 秒内已经探过/正在探，直接返回最近一次落库结果（HTTP 200），
     * 不重复打上游——不这样做时，管理页连点「测试」会把云端 API 打到限流。</p>
     *
     * @param id        主键
     * @param timeoutMs 可选超时覆盖
     * @return 探活结果
     */
    public ChannelActionDTO.Probe probe(Long id, Long timeoutMs) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");
        String lockKey = RedisKeys.adminProbe(code);
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1",
                Duration.ofSeconds(Math.max(1, properties.getProbeDebounceSeconds())));
        if (!Boolean.TRUE.equals(acquired)) {
            // 抢不到锁：回最近一次落库结果（含 health_note），不再打上游
            log.info("通道 {} 探活被防抖锁拦截，返回上次结果", code);
            ChannelActionDTO.Probe cached = new ChannelActionDTO.Probe();
            cached.setId(id);
            cached.setHealthStatus(str(row, "health_status"));
            cached.setLatencyMs(null);
            cached.setHttpStatus(null);
            cached.setModelEcho(null);
            cached.setProbedAt(toEpochMs(row.get("last_probe_at")));
            return cached;
        }

        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : probeClient.defaultTimeoutMs();
        return doProbe(row, timeout);
    }

    /**
     * 真正执行探活并回写库（同步版本，供 #78 与 #79 复用）。
     */
    private ChannelActionDTO.Probe doProbe(Map<String, Object> row, long timeoutMs) {
        Long id = lng(row, "id");
        String code = str(row, "channel_code");
        String providerType = str(row, "provider_type");
        long start = System.currentTimeMillis();
        ChannelProbeClient.ProbeOutcome outcome;
        try {
            outcome = probeClient.probe(code, providerType, str(row, "base_url"),
                    str(row, "model_name"), str(row, "api_key"), timeoutMs);
        } catch (BusinessException ex) {
            // 502 / 504：把失败原因如实写回 health_note，前端能看到「为什么挂了」
            writeProbeResult(id, "DEGRADE", ex.getMessage(), System.currentTimeMillis());
            bumpFailStreak(code, true);
            throw ex;
        }
        long probedAt = System.currentTimeMillis();
        String status = outcome.ok() ? "ON" : "DEGRADE";
        writeProbeResult(id, status, outcome.note(), probedAt);
        bumpFailStreak(code, !outcome.ok());

        ChannelActionDTO.Probe out = new ChannelActionDTO.Probe();
        out.setId(id);
        out.setHealthStatus(status);
        out.setLatencyMs(outcome.latencyMs());
        out.setHttpStatus(outcome.httpStatus());
        out.setModelEcho(outcome.modelEcho());
        out.setProbedAt(probedAt);
        log.info("通道探活 code={} http={} ok={} latency={}ms elapsed={}ms",
                code, outcome.httpStatus(), outcome.ok(), outcome.latencyMs(),
                System.currentTimeMillis() - start);
        return out;
    }

    private void writeProbeResult(Long id, String status, String note, long probedAt) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("health_status", status);
        patch.put("health_note", note == null ? null : truncate255(note));
        patch.put("last_probe_at", new Timestamp(probedAt));
        dao.update(id, patch);
        evictChannelCache();
    }

    /**
     * 维护连续失败计数 {@code oj:admin:probe:{code}:fail}。
     *
     * <p>v2.0 DDL 没有 fail_streak 列，但 #79 的响应体要求回 {@code failStreak}、
     * 语义要求「清零连续失败计数」。用一个 Redis 计数器承载最直接：
     * 探活成功 DEL、失败 INCR，恢复时 DEL。它不参与业务判定（健康态以 probe 结果为准），
     * 只作为运维观测与「是否值得自动恢复」的依据。</p>
     *
     * @param code   通道编码
     * @param failed 本次探活是否失败
     */
    private void bumpFailStreak(String code, boolean failed) {
        String key = RedisKeys.adminProbe(code) + ":fail";
        try {
            if (failed) {
                redis.opsForValue().increment(key);
                // 计数器保留一天即可，避免 Redis 里留下永不清理的键
                redis.expire(key, Duration.ofDays(1));
            } else {
                redis.delete(key);
            }
        } catch (RuntimeException e) {
            log.warn("维护通道 {} 失败计数时报错（不影响探活结果）：{}", code, e.getMessage());
        }
    }

    // ==================================================================
    // #79 降级恢复
    // ==================================================================

    /**
     * 从降级恢复：清零连续失败计数并重新探活。
     *
     * <p><b>failStreak 的存储</b>：v2.0 DDL 没有独立的失败计数列，
     * 本项目用 Redis 计数器 {@code oj:admin:probe:{code}:fail}（探活失败时 INCR，
     * 成功时 DEL）。「清零」= DEL 该 key，返回值固定 0——
     * 不回假数字，这个 0 是「清零动作刚做完」的真实结果。</p>
     *
     * @param id 主键
     * @return 恢复结果
     */
    public ChannelActionDTO.Recovered recover(Long id) {
        Map<String, Object> row = dao.findById(id);
        if (row == null) {
            throw BusinessException.notFound("通道不存在：" + id);
        }
        String code = str(row, "channel_code");
        if ("ON".equalsIgnoreCase(str(row, "health_status")) && bool(row, "is_enabled")) {
            throw BusinessException.conflict("通道 " + code + " 本就处于 ON，无需恢复");
        }

        // ① 清零失败计数
        redis.delete(RedisKeys.adminProbe(code) + ":fail");
        // ② 清掉降级标记，让探活逻辑从干净状态重新判断
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("health_status", "PROBING");
        patch.put("health_note", null);
        dao.update(id, patch);
        evictChannelCache();

        // ③ 重新探活（真发请求；失败时抛 502/504，由 GlobalExceptionHandler 转语义化错误码）
        Map<String, Object> fresh = dao.findById(id);
        long timeout = probeClient.defaultTimeoutMs();
        ChannelActionDTO.Probe probe = doProbe(fresh == null ? row : fresh, timeout);

        ChannelActionDTO.Recovered out = new ChannelActionDTO.Recovered();
        out.setId(id);
        out.setHealthStatus(probe.getHealthStatus());
        out.setRecoveredAt(System.currentTimeMillis());
        out.setFailStreak(0);
        log.info("通道恢复 id={} code={} 探活结果={}", id, code, probe.getHealthStatus());
        return out;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 清掉 AI 服务的通道配置快照。
     *
     * <p>这是本类最容易被忽略、后果最隐蔽的一步（agent.md §2.6）。
     * 任何写 {@code ai_model_config} / {@code ai_model_role} 之后都必须调用。</p>
     */
    private void evictChannelCache() {
        try {
            redis.delete(RedisKeys.aiChannelConfig());
        } catch (RuntimeException e) {
            // 缓存清不掉不能吞：记 ERROR，让运维知道 AI 服务可能还在读旧快照
            log.error("清除通道缓存 {} 失败，AI 服务可能读到旧配置", RedisKeys.aiChannelConfig(), e);
        }
    }

    /** 新增通道后的异步首探：失败只落库与日志，不影响 #74 的响应 */
    private void probeAfterCommit(Long id, String taskId) {
        try {
            Map<String, Object> row = dao.findById(id);
            if (row == null) {
                return;
            }
            ChannelActionDTO.Probe r = doProbe(row, probeClient.defaultTimeoutMs());
            log.info("首次探活完成 task={} channelId={} status={}", taskId, r.getId(), r.getHealthStatus());
        } catch (RuntimeException e) {
            log.warn("首次探活失败 task={} channelId={}：{}", taskId, id, e.getMessage());
            writeProbeResult(id, "DEGRADE", "首次探活失败：" + e.getMessage(), System.currentTimeMillis());
        }
    }

    /**
     * 事务提交后再执行（无事务上下文时立即执行）。
     *
     * <p>用 {@code TransactionSynchronizationManager} 而不是无条件异步：新增通道走
     * {@code @Transactional}，异步探活线程可能读到未提交的行，表现为
     * 「探活成功但库里查不到这条通道」。没有事务时（如单元测试）直接跑。</p>
     *
     * @param action 待执行动作
     */
    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }

    private ChannelPageDTO.Item toItem(Map<String, Object> row, List<String> roles,
                                       Map<String, Object> agg) {
        ChannelPageDTO.Item item = new ChannelPageDTO.Item();
        item.setId(lng(row, "id"));
        item.setChannelCode(str(row, "channel_code"));
        item.setChannelName(str(row, "channel_name"));
        item.setVendor(str(row, "vendor"));
        item.setModelName(str(row, "model_name"));
        item.setRoleBindings(roles == null ? new ArrayList<>() : roles);
        item.setBaseUrl(str(row, "base_url"));
        item.setTimeoutMs(secondsToMs(intOf(row, "timeout_seconds")));
        item.setMaxTokens(intOf(row, "max_tokens"));
        item.setTemperature(row.get("temperature") == null ? null : dbl(row, "temperature"));
        item.setMaxConcurrency(intOf(row, "max_concurrency"));
        item.setIsEnabled(bool(row, "is_enabled"));
        item.setHealthStatus(str(row, "health_status"));
        item.setHealthNote(str(row, "health_note"));
        item.setAvgTtftMs(avgTtft(agg));
        item.setTodayCalls(agg == null ? 0L : lng(agg, "calls"));
        item.setTokensIn(agg == null ? 0L : lng(agg, "tokensIn"));
        item.setTokensOut(agg == null ? 0L : lng(agg, "tokensOut"));
        item.setCostCents(agg == null ? 0L : lng(agg, "costCents"));
        item.setLastProbeAt(toEpochMs(row.get("last_probe_at")));
        return item;
    }

    private ChannelPageDTO.Summary buildSummary(List<ChannelPageDTO.Item> all) {
        int on = 0;
        int degrade = 0;
        int off = 0;
        long todayTotal = 0L;
        for (ChannelPageDTO.Item item : all) {
            todayTotal += item.getTodayCalls() == null ? 0L : item.getTodayCalls();
            if (!Boolean.TRUE.equals(item.getIsEnabled())) {
                off++;
                continue;
            }
            String status = item.getHealthStatus() == null ? "" : item.getHealthStatus();
            switch (status) {
                case "ON" -> on++;
                case "DEGRADE" -> degrade++;
                case "OFF" -> off++;
                default -> {
                    // PROBING 计入「非在线」，与契约「degraded = 至少一个通道非 ON」的口径一致
                    degrade++;
                }
            }
        }
        ChannelPageDTO.Summary s = new ChannelPageDTO.Summary();
        s.setOn(on);
        s.setDegrade(degrade);
        s.setOff(off);
        s.setTodayTotal(todayTotal);
        return s;
    }

    private boolean matchesState(ChannelPageDTO.Item item, String filter) {
        if ("all".equals(filter)) {
            return true;
        }
        boolean enabled = Boolean.TRUE.equals(item.getIsEnabled());
        String status = item.getHealthStatus() == null ? "" : item.getHealthStatus().toLowerCase(Locale.ROOT);
        return switch (filter) {
            case "on" -> enabled && "on".equals(status);
            case "degrade" -> "degrade".equals(status);
            case "off" -> !enabled || "off".equals(status);
            case "probing" -> "probing".equals(status);
            default -> true;
        };
    }

    private boolean matchesKw(ChannelPageDTO.Item item, String kw) {
        if (kw == null || kw.isBlank()) {
            return true;
        }
        String needle = kw.trim().toLowerCase(Locale.ROOT);
        return contains(item.getChannelCode(), needle)
                || contains(item.getChannelName(), needle)
                || contains(item.getVendor(), needle)
                || contains(item.getModelName(), needle)
                || contains(item.getBaseUrl(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Comparator<ChannelPageDTO.Item> comparator(String sortKey, boolean desc) {
        Comparator<ChannelPageDTO.Item> c = switch (sortKey) {
            case "name" -> Comparator.comparing(i -> i.getChannelName() == null ? "" : i.getChannelName());
            case "ttft" -> Comparator.comparing(i -> i.getAvgTtftMs() == null ? Long.MAX_VALUE : i.getAvgTtftMs());
            default -> Comparator.comparing(i -> i.getTodayCalls() == null ? 0L : i.getTodayCalls());
        };
        return desc ? c.reversed() : c;
    }

    private List<ChannelDetailDTO.TrendPoint> trend24h(String code) {
        // 补齐 24 小时：前端画折线时需要等距的 x 轴，缺桶会让图形被压缩
        Map<Integer, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : dao.trend24h(code, LocalDateTime.now().minusHours(24))) {
            buckets.put((int) lng(row, "h"), row);
        }
        List<ChannelDetailDTO.TrendPoint> out = new ArrayList<>(24);
        int nowHour = LocalDateTime.now().getHour();
        for (int i = 23; i >= 0; i--) {
            int hour = Math.floorMod(nowHour - i, 24);
            Map<String, Object> row = buckets.get(hour);
            ChannelDetailDTO.TrendPoint p = new ChannelDetailDTO.TrendPoint();
            p.setHour(hour);
            p.setCalls(row == null ? 0L : lng(row, "calls"));
            p.setTtftMs(row == null || row.get("avg_ttft") == null ? null : Math.round(dbl(row, "avg_ttft")));
            out.add(p);
        }
        return out;
    }

    private List<ChannelDetailDTO.RecentCall> recentCalls(String code) {
        List<ChannelDetailDTO.RecentCall> out = new ArrayList<>();
        for (Map<String, Object> row : dao.recentCalls(code, 10)) {
            ChannelDetailDTO.RecentCall c = new ChannelDetailDTO.RecentCall();
            c.setRecordId(lng(row, "id"));
            c.setAt(toEpochMs(row.get("create_time")));
            c.setTtftMs(row.get("ttft_ms") == null ? null : lng(row, "ttft_ms"));
            c.setTokens(lng(row, "tokens_in") + lng(row, "tokens_out"));
            c.setGuardCode(guardCode(intOf(row, "guardrail_action")));
            out.add(c);
        }
        return out;
    }

    private List<ChannelDetailDTO.DegradeEvent> degradeEvents() {
        List<ChannelDetailDTO.DegradeEvent> out = new ArrayList<>();
        for (Map<String, Object> row : dao.degradeEvents(10)) {
            ChannelDetailDTO.DegradeEvent e = new ChannelDetailDTO.DegradeEvent();
            e.setAt(toEpochMs(row.get("create_time")));
            e.setFrom(str(row, "selected_channel"));
            e.setTo(str(row, "actual_model"));
            e.setReason("端云降级：上游超时/不可用，已回落到本地通道");
            out.add(e);
        }
        return out;
    }

    /** 契约 §7.4 备注的三分类派生：0 pass / 1 block / 2 rewrite */
    public static String guardCode(Integer action) {
        if (action == null) {
            return "pass";
        }
        return switch (action) {
            case 1 -> "block";
            case 2 -> "rewrite";
            default -> "pass";
        };
    }

    private static Long avgTtft(Map<String, Object> agg) {
        if (agg == null || agg.get("avgTtft") == null) {
            return null;
        }
        Object v = agg.get("avgTtft");
        return v instanceof Number n ? Math.round(n.doubleValue()) : null;
    }

    private String requireCode(ChannelCommandDTO dto) {
        String code = requireText(dto.getChannelCode(), "channelCode").trim();
        if (!code.matches(CODE_PATTERN)) {
            throw BusinessException.badRequest("channelCode 格式不合法，应为 ^[a-z][a-z0-9-]{2,31}$（如 local-qwen）");
        }
        return code;
    }

    private List<String> normalizeRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BusinessException.badRequest("roleBindings 不能为空（至少绑定 ROUTER / TUTOR / GUARDRAIL 之一）");
        }
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String role = r.trim().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw BusinessException.badRequest("roleBindings 含非法角色：" + r + "（仅支持 ROUTER / TUTOR / GUARDRAIL）");
            }
            if (!out.contains(role)) {
                out.add(role);
            }
        }
        if (out.isEmpty()) {
            throw BusinessException.badRequest("roleBindings 不能为空");
        }
        return out;
    }

    private static String normalizeStateFilter(String state) {
        if (state == null || state.isBlank()) {
            return "all";
        }
        String s = state.trim().toLowerCase(Locale.ROOT);
        if (!STATE_FILTERS.contains(s)) {
            throw BusinessException.badRequest("state 只支持 all / on / degrade / off / probing");
        }
        return s;
    }

    /** vendor → provider_type 推断（契约未要求前端填 providerType） */
    private static String providerTypeOf(String vendor, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return normalizeProviderType(explicit);
        }
        String v = vendor == null ? "" : vendor.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ollama") ? "OLLAMA" : "OPENAI_COMPATIBLE";
    }

    private static String normalizeProviderType(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!"OLLAMA".equals(v) && !"OPENAI_COMPATIBLE".equals(v)) {
            throw BusinessException.badRequest("providerType 只支持 OLLAMA / OPENAI_COMPATIBLE");
        }
        return v;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    /** 秒 → 毫秒（DB 只有 timeout_seconds，对外统一毫秒） */
    private static Long secondsToMs(Integer seconds) {
        return seconds == null ? null : seconds * 1000L;
    }

    /** 毫秒 → 秒，向上取整：800ms 不该被写成 0 秒 */
    private static Integer msToSeconds(Long ms) {
        if (ms == null || ms <= 0) {
            return 8;
        }
        long seconds = (ms + 999) / 1000;
        if (seconds < 1 || seconds > 600) {
            throw BusinessException.badRequest("timeoutMs 超出合理范围（1000 ~ 600000）");
        }
        return (int) seconds;
    }

    private static Integer clamp(Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            throw BusinessException.badRequest("取值超出范围（" + min + " ~ " + max + "）：" + value);
        }
        return value;
    }

    private static Double clampTemp(Double t) {
        if (t == null) {
            return 0.30;
        }
        if (t < 0.0 || t > 1.0) {
            throw BusinessException.badRequest("temperature 必须在 0.00 ~ 1.00 之间");
        }
        return t;
    }

    private static String truncate255(String s) {
        return s.length() <= 255 ? s : s.substring(0, 254) + "…";
    }

    private static LocalDateTime todayStart() {
        return LocalDateTime.now().toLocalDate().atStartOfDay();
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static long lng(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static double dbl(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof Number n && n.intValue() != 0;
    }

    /** DATETIME / Timestamp / LocalDateTime → 毫秒时间戳 */
    private static Long toEpochMs(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.util.Date d) {
            return d.getTime();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    /**
     * 当前通道列表（供健康聚合复用，避免两处各写一份计数逻辑）。
     *
     * @return 全部通道条目
     */
    public List<ChannelPageDTO.Item> allChannels() {
        Map<String, List<String>> roles = dao.loadRoleBindings();
        Map<String, Map<String, Object>> agg = dao.todayAggByChannel(todayStart());
        List<ChannelPageDTO.Item> out = new ArrayList<>();
        for (Map<String, Object> row : dao.findAll()) {
            out.add(toItem(row, roles.get(str(row, "channel_code")), agg.get(str(row, "channel_code"))));
        }
        return out;
    }

    /**
     * 通道健康四档计数（健康聚合 §7.3.1 / 统计 §7.6.1 共用）。
     *
     * @return {@code {on, degrade, off, total}}
     */
    public Map<String, Integer> healthBuckets() {
        int on = 0;
        int degrade = 0;
        int off = 0;
        int total = 0;
        for (Map<String, Object> row : dao.healthBuckets()) {
            int cnt = intOf(row, "cnt");
            total += cnt;
            boolean enabled = intOf(row, "is_enabled") == 1;
            String status = str(row, "health_status");
            if (!enabled || "OFF".equalsIgnoreCase(status)) {
                off += cnt;
            } else if ("ON".equalsIgnoreCase(status)) {
                on += cnt;
            } else {
                degrade += cnt;
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("on", on);
        out.put("degrade", degrade);
        out.put("off", off);
        out.put("total", total);
        return out;
    }

    /**
     * 今日全站 AI 调用总数。
     *
     * @return 调用数
     */
    public long todayCalls() {
        return dao.countCallsSince(todayStart());
    }
}
