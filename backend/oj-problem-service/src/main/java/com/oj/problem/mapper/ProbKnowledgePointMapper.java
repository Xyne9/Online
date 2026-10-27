package com.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.oj.problem.entity.ProbKnowledgePoint;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识点字典 Mapper（纯字典，10 条，全量缓存级数据）。
 *
 * <p>只用来把 {@code prob_question.knowledge_point_id} 翻成人类可读的
 * {@code knowledgePoint} 名称（编辑态 {@code ProblemEditDTO} 需要）。</p>
 *
 * @author oj-cloud
 */
@Mapper
public interface ProbKnowledgePointMapper extends BaseMapper<ProbKnowledgePoint> {
}
