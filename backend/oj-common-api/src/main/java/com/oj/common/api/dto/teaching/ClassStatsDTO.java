package com.oj.common.api.dto.teaching;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 班级维度统计（`TeachingAggregateFeignClient#getClassStats` 的响应体）。
 *
 * <p>管理端全站统计大盘的班级维度来源，避免 admin 服务自己去连教务库（服务边界不允许）。</p>
 *
 * @author oj-cloud
 */
@Data
public class ClassStatsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 班级 ID */
    private Long classId;

    /** 班级名称 */
    private String className;

    /** 统计区间标识：7d / 30d / term / all */
    private String range;

    /** 学生人数 */
    private Integer studentCount;

    /** 作业总数（区间内） */
    private Integer assignmentCount;

    /** 平均提交率（0~100） */
    private Double submitRate;

    /** 平均分 */
    private Double avgScore;

    /** 通过率（0~100） */
    private Double acRate;

    /** 活跃学生数（区间内有提交） */
    private Integer activeStudents;

    /** AI 提示调用总次数 */
    private Long aiCallCount;

    /** 查重命中次数 */
    private Integer plagiarismHitCount;

    /** 分数分布（区间：人数），如 {"0-59": 3, "60-79": 12, "80-100": 35} */
    private java.util.Map<String, Integer> scoreDistribution;

    /** 逐日提交趋势（日期 → 提交数） */
    private List<TrendPointDTO> submitTrend;

    /** 趋势点：某天的提交数 */
    @Data
    public static class TrendPointDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 日期，格式 yyyy-MM-dd */
        private String date;

        /** 当天提交数 */
        private Integer count;

        /** 当天 AC 数 */
        private Integer acCount;
    }
}
