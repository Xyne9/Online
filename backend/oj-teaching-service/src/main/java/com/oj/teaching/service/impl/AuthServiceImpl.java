package com.oj.teaching.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oj.common.core.exception.BusinessException;
import com.oj.common.core.jwt.JwtUtil;
import com.oj.common.core.jwt.RoleConstants;
import com.oj.common.core.redis.RedisKeys;
import com.oj.common.web.context.UserContext;
import com.oj.teaching.config.JwtConfig;
import com.oj.teaching.constant.AuthConstants;
import com.oj.teaching.dto.CurrentUserVO;
import com.oj.teaching.dto.LoginRequest;
import com.oj.teaching.dto.LoginResultVO;
import com.oj.teaching.dto.LoginUserVO;
import com.oj.teaching.dto.MenuVO;
import com.oj.teaching.dto.RefreshResultVO;
import com.oj.teaching.entity.SysUser;
import com.oj.teaching.entity.TeachClass;
import com.oj.teaching.mapper.AuthBadgeMapper;
import com.oj.teaching.mapper.SysUserMapper;
import com.oj.teaching.mapper.TeachClassMapper;
import com.oj.teaching.service.AuthService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * 认证服务实现（契约 §2.2–§2.6 逐条落地）。
 *
 * <p><b>鉴权链路（契约 §2.1）：</b></p>
 * <pre>
 * 浏览器 ──login──▶ 本类 ──BCrypt.matches──▶ sys_user
 *                     ├─ 签 JWT（HS256，载荷见 §2.7）
 *                     ├─ SET oj:auth:token:{userId}   = {"accessToken":..,"jti":..}  EX 24h
 *                     └─ SET oj:auth:refresh:{userId} = {"token":..,"jti":..}       EX 7d / 12h
 * </pre>
 *
 * <p><b>三条不可动摇的实现纪律：</b></p>
 * <ol>
 *   <li><b>Redis Key 一律走 {@link RedisKeys} 静态方法</b>，绝不出现手写的
 *       {@code "oj:auth:token:"}——一旦拼写漂移，就会出现「登录成功但网关一直 401」
 *       这种几乎无法通过日志定位的幽灵 bug。</li>
 *   <li><b>登录失败文案统一</b>为「账号或密码错误」，账号不存在与密码错误<b>文案与耗时都一致</b>：
 *       「用户不存在」分支也要对一个随机 dummy 哈希跑一次 BCrypt，否则响应时间差
 *       就能被用来做账号枚举。</li>
 *   <li><b>密码明文绝不落库、绝不进日志、绝不进响应</b>。{@link SysUser#getPassword()}
 *       只在 {@link #login} 内部读一次。</li>
 * </ol>
 *
 * @author oj-cloud
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /**
     * 账号不存在时用于「陪跑」的 BCrypt 哈希。
     *
     * <p>进程启动时用一段随机 UUID 现算一次（cost=10，与种子数据同强度），
     * 因此它的明文<b>无人知晓也不可复现</b>。唯一用途是让「用户不存在」分支
     * 也消耗一次等量 BCrypt 运算，消除时序侧信道。</p>
     */
    private static final String DUMMY_BCRYPT_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    /** BCrypt 校验器：cost 由库里的哈希自身携带，matches 与本处的 strength 无关 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final SysUserMapper sysUserMapper;
    private final TeachClassMapper teachClassMapper;
    private final AuthBadgeMapper authBadgeMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtConfig jwtConfig;

    /** 登录限流阈值（次 / 60 秒），来自 {@code oj.login.rate-limit-per-minute} */
    @Value("${oj.login.rate-limit-per-minute:10}")
    private int loginRateLimit;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           TeachClassMapper teachClassMapper,
                           AuthBadgeMapper authBadgeMapper,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           JwtConfig jwtConfig) {
        this.sysUserMapper = sysUserMapper;
        this.teachClassMapper = teachClassMapper;
        this.authBadgeMapper = authBadgeMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jwtConfig = jwtConfig;
    }

    // ==================================================================
    // #1 登录（契约 §2.2）
    // ==================================================================

    @Override
    public LoginResultVO login(LoginRequest request, String clientIp) {
        // ① 参数校验（400）。契约 §2.2：username 2–32 字符、password 6–64 字符。
        String username = request.resolveUsername();
        if (username == null || username.isEmpty()) {
            throw BusinessException.badRequest("账号不能为空");
        }
        if (username.length() < 2 || username.length() > 32) {
            throw BusinessException.badRequest("账号长度需在 2–32 个字符之间");
        }
        String rawPassword = request.getPassword();
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw BusinessException.badRequest("密码不能为空");
        }
        if (rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw BusinessException.badRequest("密码长度需在 6–64 个字符之间");
        }

        // ② 同 IP 每分钟 10 次限流（429）。放在查库之前，暴力破解才真的被挡住。
        checkLoginRate(clientIp);

        // ③ 查用户 + BCrypt 校验；两条失败分支文案与耗时都一致（防账号探测）
        SysUser user = sysUserMapper.selectByUsername(username);
        String hashToCheck = (user == null || user.getPassword() == null)
                ? DUMMY_BCRYPT_HASH
                : user.getPassword();
        boolean passwordOk;
        try {
            passwordOk = passwordEncoder.matches(rawPassword, hashToCheck);
        } catch (IllegalArgumentException e) {
            // 实测（ResponseProbe）：spring-security-crypto 6.x 对「不是 BCrypt 形状」的哈希
            // 只打一条 WARN 然后返回 false，并不会抛异常。这里的 catch 是防御性的——
            // 若未来版本改为抛 IllegalArgumentException（历史脏数据触发），
            // 也必须收敛成「密码错误」，绝不把内部格式细节透给调用方。
            passwordOk = false;
        }
        if (user == null || !passwordOk) {
            log.warn("[auth] 登录失败 user={} ip={}", username, clientIp);
            throw BusinessException.unauthorized(AuthConstants.MSG_LOGIN_FAILED);
        }

        // ④ 账号禁用（403）。必须放在密码校验通过之后：否则「403 还是 401」
        //    本身就泄露了「这个账号确实存在」。
        if (!user.isEnabled()) {
            log.warn("[auth] 禁用账号尝试登录 user={} id={}", user.getUsername(), user.getId());
            throw BusinessException.forbidden(AuthConstants.MSG_ACCOUNT_DISABLED);
        }

        // ⑤ 角色合法性：库里 role 若不是三值之一，签出的 token 会把用户放进错误的功能域
        if (!RoleConstants.isValid(user.getRole())) {
            log.error("[auth] 用户 {} 的角色值非法：{}", user.getUsername(), user.getRole());
            throw BusinessException.forbidden("账号角色配置异常，请联系管理员");
        }

        // ⑥ 签发 access + refresh，并写入 Redis 在线态
        ClassInfo clazz = resolveClass(user);
        String accessToken = JwtUtil.createToken(user.getId(), user.getUsername(),
                user.getRole(), clazz.id());
        String accessJti = JwtUtil.getJti(JwtUtil.parse(accessToken));

        long refreshTtl = request.rememberMeFlag()
                ? RedisKeys.TTL_AUTH_REFRESH_STUDENT   // 7 天
                : RedisKeys.TTL_AUTH_REFRESH_ADMIN;    // 12 小时
        RefreshTokenCodec.Issued refreshToken = RefreshTokenCodec.issue(user.getId());

        writeAccessToken(user.getId(), accessToken, accessJti);
        writeRefreshToken(user.getId(), refreshToken.token(), refreshToken.jti(), refreshTtl);

        // ⑦ 回写最近登录时刻。失败不影响本次登录——用户已通过校验，
        //    一个统计字段写不进去不该把人挡在门外。
        try {
            sysUserMapper.touchLastLogin(user.getId(), LocalDateTime.now(AuthConstants.DB_ZONE));
        } catch (RuntimeException e) {
            log.warn("[auth] 回写 last_login_at 失败 user={}：{}", user.getUsername(), e.toString());
        }

        log.info("[auth] 登录成功 user={} role={} ip={}", user.getUsername(), user.getRole(), clientIp);

        LoginResultVO result = new LoginResultVO();
        result.setAccessToken(accessToken);
        result.setRefreshToken(refreshToken.token());
        result.setExpiresIn(jwtConfig.getExpireSeconds());
        result.setUser(buildLoginUser(user));
        result.setHomePath(homePathOf(user.getRole()));
        return result;
    }

    // ==================================================================
    // #2 注销（契约 §2.3）
    // ==================================================================

    @Override
    public void logout(boolean allDevices) {
        Long userId = UserContext.userId();   // 无网关头 → 401

        // access：删掉在线态后，网关的 Redis 逐字比对立即失配，旧 JWT 当场作废。
        redisTemplate.delete(RedisKeys.authToken(userId));

        /*
         * refresh：契约的「用途」段要求「access 与 refresh 同时失效」，而备注段写的是
         * 「allDevices=true 额外删 refresh」。本期是**单会话模型**（一个用户只有一个
         * oj:auth:refresh:{userId}），两条要求在这里汇合为同一个动作——
         * 若默认路径留着 refresh，拿旧 refresh 就能立刻换出新 access，注销形同虚设。
         * allDevices 因此保留为前向兼容字段：将来改多会话（refresh 存 Set/Hash）时，
         * 它才需要真正分叉；现在两条路径都清空，幂等且安全。
         */
        redisTemplate.delete(RedisKeys.authRefresh(userId));

        log.info("[auth] 注销成功 userId={} allDevices={}", userId, allDevices);
    }

    // ==================================================================
    // #3 续期（契约 §2.4）
    // ==================================================================

    @Override
    public RefreshResultVO refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            // 契约 §2.4 只列了 401/403 两个错误码，不新增 400：
            // 「没带来一枚有效刷新令牌」语义上就是「未通过认证」，
            // 且前端 api/http.js 收到 401 会清 token 并跳登录页，正是此时该做的事。
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }
        String token = refreshToken.trim();
        if (!token.startsWith(AuthConstants.REFRESH_TOKEN_PREFIX)) {
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }

        // ① 从令牌本身解出 userId 与 jti（Redis key 含 userId，而请求里只有令牌）
        Long userId;
        String jti;
        try {
            RefreshTokenCodec.Parsed parsed = RefreshTokenCodec.parse(token);
            userId = parsed.userId();
            jti = parsed.jti();
        } catch (RuntimeException e) {
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }

        // ② 与 Redis 逐字比对：不等即视为已轮换 / 已注销（防重放）
        String storedJson = redisTemplate.opsForValue().get(RedisKeys.authRefresh(userId));
        if (storedJson == null || storedJson.isBlank()) {
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }
        String storedToken = readJsonField(storedJson, "token");
        String storedJti = readJsonField(storedJson, "jti");
        if (storedToken == null || !storedToken.equals(token)
                || (storedJti != null && !storedJti.equals(jti))) {
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }

        // ③ 用户仍在、仍启用（认证失败 401、授权失败 403，见契约 §2.8）
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized(AuthConstants.MSG_REFRESH_INVALID);
        }
        if (!user.isEnabled()) {
            throw BusinessException.forbidden(AuthConstants.MSG_ACCOUNT_DISABLED);
        }
        if (!RoleConstants.isValid(user.getRole())) {
            throw BusinessException.forbidden("账号角色配置异常，请联系管理员");
        }

        // ④ 轮换：新 access + 新 refresh，旧 refresh 立即失效（防重放）。
        //    新 refresh 沿用原 key 的剩余 TTL：这样 rememberMe=true 承诺的是
        //    「绝对 7 天」，不会因为频繁刷新被无限续命。
        //    TTL 为 -1（无过期）/ -2（key 刚被并发删除）时才退回 12h 兜底；
        //    剩余 5 秒也是合法值，必须照原样继承，否则一次刷新就把窗口偷偷拉长到 12h。
        Long remainingTtl = redisTemplate.getExpire(RedisKeys.authRefresh(userId), TimeUnit.SECONDS);
        long refreshTtl = (remainingTtl != null && remainingTtl > 0)
                ? remainingTtl
                : RedisKeys.TTL_AUTH_REFRESH_ADMIN;

        Long tokenClassId = resolveClass(user).id();
        String accessToken = JwtUtil.createToken(user.getId(), user.getUsername(),
                user.getRole(), tokenClassId);
        String accessJti = JwtUtil.getJti(JwtUtil.parse(accessToken));
        RefreshTokenCodec.Issued newRefresh = RefreshTokenCodec.issue(user.getId());

        writeAccessToken(user.getId(), accessToken, accessJti);
        writeRefreshToken(user.getId(), newRefresh.token(), newRefresh.jti(), refreshTtl);

        log.info("[auth] 续期成功 userId={} 新 refresh TTL={}s", userId, refreshTtl);

        RefreshResultVO result = new RefreshResultVO();
        result.setAccessToken(accessToken);
        result.setRefreshToken(newRefresh.token());
        result.setExpiresIn(jwtConfig.getExpireSeconds());
        return result;
    }

    // ==================================================================
    // #4 当前用户（契约 §2.5）
    // ==================================================================

    @Override
    public CurrentUserVO currentUser() {
        Long userId = UserContext.userId();   // 无网关头 → 401
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            // token 有效但人已被删：语义上是「会话失效」，给 401 而不是 404，
            // 否则前端会把 404 当「页面不存在」而不去清 token 跳登录页
            throw BusinessException.unauthorized(AuthConstants.MSG_UNAUTHORIZED);
        }

        ClassInfo clazz = resolveClass(user);
        CurrentUserVO vo = new CurrentUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setRoleLabel(RoleConstants.labelOf(user.getRole()));
        vo.setClassId(clazz.id());
        vo.setClassName(clazz.name());
        vo.setSubtitle(subtitleOf(user.getRole(), clazz.name()));
        vo.setWeakTags(parseWeakTags(user.getWeakTags()));
        vo.setAiQuotaDefault(AuthConstants.AI_QUOTA_DEFAULT);
        vo.setLastLoginAt(toEpochMilli(user.getLastLoginAt()));
        return vo;
    }

    // ==================================================================
    // #5 导航菜单（契约 §2.6）
    // ==================================================================

    @Override
    public MenuVO menus() {
        Long userId = UserContext.userId();   // 无网关头 → 401
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized(AuthConstants.MSG_UNAUTHORIZED);
        }

        MenuVO vo = new MenuVO();
        vo.setRoleHint(user.getRole() == null ? "student" : user.getRole().toLowerCase());
        vo.setGroups(buildGroups(user));
        return vo;
    }

    /**
     * 按角色拼导航树，结构与原型 {@code shell.js:96-129} 的 {@code NAV} 一一对应。
     *
     * <p>唯一的结构性修正是 {@code a-sandbox}：它不再作为独立页面，而是挂在
     * {@code a-audit} 组内、指向 {@code a-audit.html#sandbox}（契约 §2.6 备注）。</p>
     *
     * @param user 当前用户
     * @return 分组列表（至少一组，绝不为 null）
     */
    private List<MenuVO.Group> buildGroups(SysUser user) {
        String role = user.getRole();
        List<MenuVO.Group> groups = new ArrayList<>(2);

        if (RoleConstants.TEACHER.equals(role)) {
            groups.add(group("teaching", "教学", List.of(
                    item("t-dashboard", "教师概览", "t-dashboard.html", 0),
                    item("t-problems", "题目管理", "t-problems.html", 0),
                    item("t-assignments", "教学活动", "t-assignments.html",
                            safeCount(() -> authBadgeMapper.countTeacherTodoAssignments(user.getId()))),
                    item("t-analytics", "班级学情", "t-analytics.html", 0))));
            groups.add(group("review", "监控与审查", List.of(
                    item("t-monitor", "考试监考", "t-monitor.html", 0),
                    item("t-plagiarism", "查重报告", "t-plagiarism.html",
                            safeCount(() -> authBadgeMapper.countTeacherPendingPlagiarism(user.getId()))))));
            return groups;
        }

        if (RoleConstants.ADMIN.equals(role)) {
            groups.add(group("ai", "AI 平台", List.of(
                    item("a-models", "模型通道", "a-models.html", 0),
                    item("a-audit", "调用审计", "a-audit.html", 0))));
            groups.add(group("infra", "基础设施", List.of(
                    // 幽灵页修正：a-sandbox 不再是独立页面，改挂 a-audit 的锚点
                    item("a-sandbox", "沙箱监控", "a-audit.html#sandbox", 0))));
            return groups;
        }

        // 学生（role 为 null 的历史脏数据也走这条最小权限分支）
        Long classId = user.getClassId();
        int todo = (classId == null) ? 0
                : safeCount(() -> authBadgeMapper.countStudentTodoAssignments(classId, user.getId()));
        groups.add(group("study", "学习", List.of(
                item("s-problems", "题库", "s-problems.html", 0),
                item("s-assignments", "我的作业", "s-assignments.html", todo),
                item("s-submissions", "提交记录", "s-submissions.html", 0),
                item("s-analytics", "学情分析", "s-analytics.html", 0))));
        groups.add(group("work", "正在做", List.of(
                item("s-workbench", "做题工作台", "s-workbench.html", 0))));
        return groups;
    }

    /**
     * 角标计数兜底：任何异常（表缺失、SQL 写错、连接抖动）都退化为 0。
     *
     * <p><b>为什么不让它抛：</b>角标只是个装饰性数字，因为一个统计查询失败就让整个
     * 侧边导航 500，用户会连页面都进不去。契约原文也是「查不到就给 0」。</p>
     */
    private int safeCount(IntSupplier supplier) {
        try {
            return Math.max(supplier.getAsInt(), 0);
        } catch (RuntimeException e) {
            log.warn("[auth] 角标计数失败，已降级为 0：{}", e.toString());
            return 0;
        }
    }

    private MenuVO.Group group(String key, String title, List<MenuVO.Item> items) {
        MenuVO.Group g = new MenuVO.Group();
        g.setKey(key);
        g.setTitle(title);
        g.setItems(items);
        return g;
    }

    private MenuVO.Item item(String key, String title, String path, int badge) {
        MenuVO.Item it = new MenuVO.Item();
        it.setKey(key);
        it.setTitle(title);
        it.setPath(path);
        it.setBadge(badge);
        // 服务端不知道浏览器此刻停在哪一页，高亮由前端按 path 自行判断
        it.setActive(false);
        return it;
    }

    // ==================================================================
    // Redis 读写
    // ==================================================================

    /** 写 access 在线态：{@code {"accessToken":..,"jti":..}}，TTL 24h */
    private void writeAccessToken(Long userId, String accessToken, String jti) {
        Map<String, Object> payload = new LinkedHashMap<>(4);
        payload.put("accessToken", accessToken);
        payload.put("jti", jti);
        redisTemplate.opsForValue().set(RedisKeys.authToken(userId), toJson(payload),
                RedisKeys.TTL_AUTH_TOKEN, TimeUnit.SECONDS);
    }

    /** 写 refresh：{@code {"token":..,"jti":..}}，TTL 7d / 12h */
    private void writeRefreshToken(Long userId, String refreshToken, String jti, long ttlSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>(4);
        payload.put("token", refreshToken);
        payload.put("jti", jti);
        redisTemplate.opsForValue().set(RedisKeys.authRefresh(userId), toJson(payload),
                ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 登录限流：Redis INCR + 首次设 TTL 60s。
     *
     * <p>用 INCR 而不是「先 GET 再 SET」：后者在并发下有竞态，两个请求同时读到 9
     * 会双双放行。INCR 是原子的，超阈值即拒。</p>
     *
     * @param clientIp 调用方 IP；null/空时跳过限流
     */
    private void checkLoginRate(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        String key = AuthConstants.LOGIN_RATE_KEY_PREFIX + clientIp;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis 抖动不该把登录整体打死：记告警后放行，仍有 BCrypt 校验兜底
            log.warn("[auth] 登录限流计数失败，已降级放行 ip={}：{}", clientIp, e.toString());
            return;
        }
        if (count == null) {
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(key, AuthConstants.LOGIN_RATE_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        int limit = loginRateLimit > 0 ? loginRateLimit : AuthConstants.LOGIN_RATE_LIMIT_DEFAULT;
        if (count > limit) {
            log.warn("[auth] 登录限流命中 ip={} count={} limit={}", clientIp, count, limit);
            throw BusinessException.tooManyRequests(AuthConstants.MSG_RATE_LIMITED);
        }
    }

    // ==================================================================
    // 用户视图组装
    // ==================================================================

    /** 登录响应用的用户卡（字段与契约 §2.2 示例一致，绝不含 password） */
    private LoginUserVO buildLoginUser(SysUser user) {
        ClassInfo clazz = resolveClass(user);
        LoginUserVO vo = new LoginUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setRole(user.getRole());
        vo.setRoleLabel(RoleConstants.labelOf(user.getRole()));
        vo.setClassId(clazz.id());
        vo.setClassName(clazz.name());
        vo.setSubtitle(subtitleOf(user.getRole(), clazz.name()));
        vo.setAvatarText(avatarTextOf(user.getRealName()));
        return vo;
    }

    /**
     * 用户所属班级的「ID + 名称」二元组。
     *
     * @param id   班级 ID；管理员 / 未带班 / 班级行缺失时为 null
     * @param name 班级名称；同上为 null
     */
    private record ClassInfo(Long id, String name) {
    }

    /**
     * 解析用户所属班级，<b>一次查库同时拿到 id 与 name</b>。
     *
     * <p>学生：{@code sys_user.class_id} 指向的班；教师：{@code teach_class.teacher_id}
     * 反查到的第一个班（契约 §2.7「教师为所带班」）；管理员：恒为 null。</p>
     *
     * <p>把 id 和 name 合在一次查询里返回，是因为教师场景两者都要用：
     * 分开写会变成「同一个班级查两遍」，而登录是热路径。更重要的是——分开写时
     * 只要有一条路径改了筛选条件（比如教师取「第一个班」vs「任一班」），
     * 就会出现「JWT 里 cid=1、响应里 className 却是 2 班」的静默不一致。</p>
     *
     * @param user 用户
     * @return 班级信息，字段可能为 null 但对象本身绝不为 null
     */
    private ClassInfo resolveClass(SysUser user) {
        if (RoleConstants.ADMIN.equals(user.getRole())) {
            return new ClassInfo(null, null);
        }
        if (RoleConstants.TEACHER.equals(user.getRole())) {
            TeachClass clazz = teachClassMapper.selectFirstByTeacherId(user.getId());
            return clazz == null ? new ClassInfo(null, null)
                    : new ClassInfo(clazz.getId(), clazz.getClassName());
        }
        if (user.getClassId() == null) {
            return new ClassInfo(null, null);
        }
        TeachClass clazz = teachClassMapper.selectById(user.getClassId());
        return clazz == null ? new ClassInfo(null, null)
                : new ClassInfo(clazz.getId(), clazz.getClassName());
    }

    /**
     * 身份副标题（原型 {@code shell.js:152} 第三行文案的来源）。
     *
     * <p>学生：班级名；教师：「班级 · 主讲教师」；管理员：「系统与模型运维」。</p>
     */
    private String subtitleOf(String role, String className) {
        if (RoleConstants.ADMIN.equals(role)) {
            return "系统与模型运维";
        }
        if (RoleConstants.TEACHER.equals(role)) {
            return (className == null || className.isBlank()) ? "主讲教师" : className + " · 主讲教师";
        }
        return className;
    }

    /** 头像字：真实姓名首字（前端 .avatar 直接渲染单个汉字） */
    private String avatarTextOf(String realName) {
        if (realName == null || realName.isBlank()) {
            return "?";
        }
        return realName.trim().substring(0, 1);
    }

    /**
     * 解析 {@code sys_user.weak_tags}（JSON 列）为字符串数组。
     *
     * <p>解析失败、值为 null、不是数组、元素不是字符串——一律退化为空数组。
     * 契约 §2.5 的 {@code weakTags} 是「薄弱知识点名数组」，绝不能返回 null：
     * 前端会直接对它 {@code .map()}，null 会抛异常把整张用户卡打断。</p>
     */
    private List<String> parseWeakTags(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> tags = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                if (element != null && element.isTextual()) {
                    String text = element.asText();
                    if (text != null && !text.isBlank()) {
                        tags.add(text);
                    }
                }
            }
            return tags;
        } catch (Exception e) {
            log.warn("[auth] weak_tags 解析失败，已降级为空数组：{}", e.toString());
            return List.of();
        }
    }

    /** 角色 → 落地页（契约 §2.2 备注：落地判断不在前端硬编码） */
    private String homePathOf(String role) {
        if (RoleConstants.TEACHER.equals(role)) {
            return AuthConstants.HOME_TEACHER;
        }
        if (RoleConstants.ADMIN.equals(role)) {
            return AuthConstants.HOME_ADMIN;
        }
        if (RoleConstants.STUDENT.equals(role)) {
            return AuthConstants.HOME_STUDENT;
        }
        return AuthConstants.HOME_FALLBACK;
    }

    /**
     * {@code LocalDateTime} → epoch 毫秒；null 保持 null（契约：时间字段一律 long 毫秒）。
     *
     * <p>用 {@link AuthConstants#DB_ZONE} 而不是 {@code systemDefault()}：库里存的是
     * 按 {@code serverTimezone=Asia/Shanghai} 解释的墙钟时间，跟着 JVM 默认时区走
     * 会在 UTC 容器里整体偏 8 小时。</p>
     */
    private Long toEpochMilli(LocalDateTime time) {
        return time == null ? null : time.atZone(AuthConstants.DB_ZONE).toInstant().toEpochMilli();
    }

    // ==================================================================
    // JSON 小工具（不引新依赖，直接用 Boot 自带的 Jackson）
    // ==================================================================

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 序列化一个全是 String 的 Map 失败只可能是环境级故障
            throw BusinessException.internalError("登录态序列化失败");
        }
    }

    /** 读 JSON 字符串里的某个顶层字段；解析失败或字段缺失返回 null */
    private String readJsonField(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode value = node == null ? null : node.get(field);
            return (value == null || value.isNull()) ? null : value.asText();
        } catch (Exception e) {
            log.warn("[auth] Redis 登录态 JSON 解析失败：{}", e.toString());
            return null;
        }
    }
}
