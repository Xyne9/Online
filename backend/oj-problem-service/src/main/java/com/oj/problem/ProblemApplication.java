package com.oj.problem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 题库服务启动类（端口 8082）。
 *
 * <p>承载（设计文档 §1.2）：</p>
 * <ul>
 *   <li>题目 CRUD：{@code /api/problem/**}；</li>
 *   <li>逐测试点实体管理（{@code prob_test_case}，含分值/顺序/是否样例）；</li>
 *   <li>样例运行（转发判题服务的沙箱能力）。</li>
 * </ul>
 *
 * <p><b>公共基座装配：</b>{@code com.oj.common.web} 不在本类的组件扫描范围内
 * （默认只扫 {@code com.oj.problem} 及其子包），由 {@code config/CommonWebConfig}
 * 显式 {@code @ComponentScan} 引入 —— 否则 {@code GlobalExceptionHandler} 不生效，
 * 所有业务异常都会退化成 500，「code 与 HTTP 状态码严格同值」这条铁律当场失效；
 * 同时 {@code UserContext} 读不到网关下发的 {@code X-User-*} 头，
 * 学生视角与「我的状态」会全部判错。</p>
 *
 * <p><b>Mapper 注册：</b>本服务的 Mapper 接口都标了 {@code @Mapper}，
 * 由 MyBatis-Plus 的自动扫描（范围 = 本启动类所在包及子包）注册，
 * 与 teaching / judge 两个服务保持同一种形态，不额外写 {@code @MapperScan}。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ProblemApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProblemApplication.class, args);
    }
}
