package com.oj.problem.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code com.oj.common.web} 的公共基座装配进本服务。
 *
 * <p><b>为什么必须有这个类：</b>Spring Boot 的组件扫描范围默认只有启动类所在包及其子包
 * （这里是 {@code com.oj.problem}）。{@code oj-common-web} 的包是 {@code com.oj.common.web}，
 * 不在扫描范围内——不显式加这一行，{@code GlobalExceptionHandler} 根本不会注册，
 * 于是所有 {@code BusinessException}（比如 sortBy 越界该返回 400）都会退化成
 * Spring 默认的 500 白页，「code 与 HTTP 状态码严格同值」这条铁律当场失效。</p>
 *
 * <p>顺带装配进来的还有 {@code MybatisPlusConfig}（分页拦截器）——
 * 题库列表的 {@code page/pageSize} 就靠它，缺了会返回全表。</p>
 *
 * <p><b>为什么不直接改 {@code ProblemApplication} 的 {@code scanBasePackages}：</b>
 * 与 teaching 服务保持同一形态（每个服务一个同款配置类），启动类只当入口，
 * 避免 5 个服务各写一套扫描范围后互相漂移。</p>
 *
 * @author oj-cloud
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan("com.oj.common.web")
public class CommonWebConfig {
}
