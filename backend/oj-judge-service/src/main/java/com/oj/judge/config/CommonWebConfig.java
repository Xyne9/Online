package com.oj.judge.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code com.oj.common.web} 的公共基座装配进本服务。
 *
 * <p><b>为什么必须有这个类：</b>Spring Boot 的组件扫描范围默认只有启动类所在包及其子包
 * （这里是 {@code com.oj.judge}）。{@code oj-common-web} 的包是 {@code com.oj.common.web}，
 * 不在扫描范围内——不显式加这一行，{@code GlobalExceptionHandler} 根本不会注册，
 * 于是 {@code BusinessException}（3 秒防抖锁该返回 429、沙箱故障该返回 502）
 * 都会退化成 Spring 默认的 500，「code 与 HTTP 状态码严格同值」这条铁律当场失效，
 * 前端 {@code api/http.js} 的重试与降级判断也全部失灵。</p>
 *
 * <p>同时装配 {@code MybatisPlusConfig}（分页拦截器），提交记录列表的
 * {@code page/pageSize} 依赖它。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.oj.common.web")
public class CommonWebConfig {
}
