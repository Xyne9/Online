package com.oj.problem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识点字典表 {@code prob_knowledge_point}。
 *
 * <p>纯字典（无软删列、无时间列），全量 10 条，题目通过
 * {@code prob_question.knowledge_point_id}（主知识点）与
 * {@code prob_question_kp}（多对多权重表）关联。</p>
 *
 * @author oj-cloud
 */
@Data
@TableName("prob_knowledge_point")
public class ProbKnowledgePoint implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识点名称，唯一，如「动态规划」「图论」 */
    private String name;

    /** 排序号，小的在前（1~5 是主干，100+ 是补充） */
    private Integer sortOrder;
}
