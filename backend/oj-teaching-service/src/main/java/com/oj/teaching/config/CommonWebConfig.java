package com.oj.teaching.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code com.oj.common.web} 的公共基座装配进本服务。
 *
 * <p><b>为什么必须有这个类：</b>Spring Boot 的组件扫描范围默认只有启动类所在包及其子包
 * （这里是 {@code com.oj.teaching}）。{@code oj-common-web} 的包是 {@code com.oj.common.web}，
 * 不在扫描范围内——不显式加这一行，{@code GlobalExceptionHandler} 根本不会注册，
 * 于是所有 {@code BusinessException} 都会退化成 Spring 默认的 500 白页，
 * 「code 与 HTTP 状态码严格同值」这条铁律当场失效；{@code TraceIdFilter} 也不会生效，
 * 响应里的 {@code traceId} 与日志、网关响应头三者对不上。</p>
 *
 * <p>顺带装配进来的还有 {@code MybatisPlusConfig}（分页拦截器）——
 * 本期认证接口用不到分页，但下一批 {@code /api/teaching/**} 端点要用，
 * 一次配好，后续不用再改启动类。</p>
 *
 * <p><b>为什么不直接改 {@code TeachingApplication} 的 {@code scanBasePackages}：</b>
 * 那样启动类会同时承担「应用入口」与「公共包扫描」两件事，而且 5 个服务各写一遍容易漂移；
 * 独立成配置类后，任何一个服务只要依赖了 {@code oj-common-web} 并加一个同款类即可。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.oj.common.web")
public class CommonWebConfig {
}
