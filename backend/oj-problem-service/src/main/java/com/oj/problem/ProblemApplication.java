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
 * <p><b>第 2 步（当前）</b>：仅空壳 + 健康检查，业务接口在第 4 步实现。</p>
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
