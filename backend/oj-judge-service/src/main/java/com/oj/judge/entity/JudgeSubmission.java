package com.oj.judge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 判题提交记录（{@code judge_submission}，设计文档 §3 表 12）。
 *
 * <p><b>字段与 DB 严格一一对应</b>（{@code DESC judge_submission} 实测 19 列），
 * 驼峰 ↔ 下划线由 MyBatis-Plus 的 {@code map-underscore-to-camel-case} 自动映射。</p>
 *
 * <p><b>单位约定（基线决策 §5.5，务必记牢）：</b></p>
 * <ul>
 *   <li>{@code timeUsed} = 最大测试点耗时，<b>毫秒</b>（不是纳秒）；</li>
 *   <li>{@code memoryUsed} = 最大测试点内存，<b>KB</b>（不是字节、不是 MB）。
 *       go-judge 回的字节在 {@code JudgeAdapter.bytesToKb} 里已换算完；</li>
 *   <li>{@code createTime} / {@code finishedAt} 是 {@code DATETIME}，接口层要转 epoch 毫秒。</li>
 * </ul>
 *
 * <p><b>为什么 {@code caseDetails} 用 String 而不是 JSON 对象：</b>
 * 表里是 MySQL {@code JSON} 列，MyBatis-Plus 默认按 {@code VARCHAR} 读写即可；
 * 若映射成 {@code List<Map>} 需要在 {@code @TableName} 上开 {@code autoResultMap}
 * 并配 {@code JacksonTypeHandler}，一旦配置漂移就静默变成 null。
 * 这里显式存 JSON 字符串、在 service 里用 ObjectMapper 解析，行为可预期。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("judge_submission")
public class JudgeSubmission {

    /** 主键（AUTO_INCREMENT；当前位数 ≤15 位，可安全以 JSON number 下发） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示号 {@code S-20481}（Redis {@code oj:seq:submission} 生成，永不复用） */
    private String displayNo;

    /** 提交者用户 ID */
    private Long userId;

    /** 题目 ID */
    private Long problemId;

    /** 所属教学活动 ID（0 = 自由题库练习） */
    private Long assignmentId;

    /** 语言：cpp / java / python */
    private String language;

    /** 完整源码（TEXT） */
    private String code;

    /** 状态机 0~9，见 {@code JudgeStatusEnum} */
    private Integer status;

    /** OI 按测试点权重累计得分（0~100） */
    private Integer oiScore;

    /** 通过的测试点个数 */
    private Integer passCaseCount;

    /** 总测试点个数 */
    private Integer totalCaseCount;

    /** 逐测试点明细 JSON 数组 */
    private String caseDetails;

    /** 最大测试点耗时（毫秒） */
    private Integer timeUsed;

    /** 最大测试点内存（KB） */
    private Integer memoryUsed;

    /** 编译错误或运行时异常日志 */
    private String errorMsg;

    /** 首个未通过测试点的序号（1~N，契约 {@code firstFailedCaseId} 字段沿用此语义） */
    private Integer firstFailedCaseId;

    /** 首个失败点的脱敏特征 JSON（给 AI 阶段③ 用，不含完整隐藏用例） */
    private String firstFailedSummary;

    /** 关联沙箱实例展示号 */
    private String sandboxInstanceId;

    /** 重判次数 */
    private Integer rejudgeCount;

    /** 是否逾期补交：0 正常 / 1 逾期 */
    private Integer isOverdue;

    /** 判题完成时刻（DATETIME） */
    private LocalDateTime finishedAt;

    /** 教务已幂等接收成绩的时刻；空值由后台任务继续同步。 */
    private LocalDateTime teachingSyncedAt;

    /** 创建时刻（DATETIME，DB 默认 CURRENT_TIMESTAMP） */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createTime;
}
