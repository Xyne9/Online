package com.oj.teaching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 教务服务启动类（端口 8081）。
 *
 * <p>承载三块职责（设计文档 §1.2）：</p>
 * <ul>
 *   <li><b>认证</b>：{@code /api/auth/**} —— 登录、刷新、注销、当前用户；</li>
 *   <li><b>教务</b>：{@code /api/teaching/**} —— 班级、作业、考试、提交记录、成绩；</li>
 *   <li><b>查重</b>：双阶段查重（精确匹配 + 语义相似度）。</li>
 * </ul>
 *
 * <p><b>第 2 步（当前）</b>：仅空壳 + 健康检查，业务接口在第 4 步实现。</p>
 *
 * @author oj-cloud
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.oj.common.api.feign")
public class TeachingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeachingApplication.class, args);
    }
}
