package com.oj.common.api.constant;

/**
 * 服务间内部调用的公共常量。
 *
 * <p>设计文档 §6 开头明确规定：内部调用统一校验请求头 {@code X-Inner-Token}。
 * 这样即使网关被绕过（内网直连），服务也不会被外部随意调用内部接口。</p>
 *
 * @author oj-cloud
 */
public final class InnerConstants {

    /** 内部调用令牌请求头名 */
    public static final String INNER_TOKEN_HEADER = "X-Inner-Token";

    /** 链路追踪请求头名（网关生成，全链路透传） */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** Feign 传递的用户 ID 头（网关鉴权通过后注入，服务层越权校验用） */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** Feign 传递的角色头 */
    public static final String USER_ROLE_HEADER = "X-User-Role";

    /** Feign 传递的班级 ID 头 */
    public static final String CLASS_ID_HEADER = "X-Class-Id";

    private InnerConstants() {
    }
}
