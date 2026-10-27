package com.oj.admin.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code oj-common-web} 的组件纳入扫描。
 *
 * <p><b>不加这个类的后果</b>（agent.md §4.5.3）：Spring Boot 默认只扫启动类所在包
 * {@code com.oj.admin}，而 {@code GlobalExceptionHandler} / {@code TraceIdFilter} 在
 * {@code com.oj.common.web} 下。缺了它，本服务抛出的 {@code BusinessException}
 * （403 越权、404 通道不存在、409 状态冲突、502 上游失败）会全部退化成 500 白页，
 * 「code 与 HTTP 状态码严格同值」当场失效——而 admin 恰恰是全站错误码最密集的服务。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.oj.common.web")
public class CommonWebConfig {
}
