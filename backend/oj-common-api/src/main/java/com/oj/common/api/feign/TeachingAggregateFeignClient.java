package com.oj.common.api.feign;

import com.oj.common.api.dto.teaching.AssignmentBriefDTO;
import com.oj.common.api.dto.teaching.ClassStatsDTO;
import com.oj.common.api.dto.teaching.ExamRuntimeDTO;
import com.oj.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * ⑤ 教务聚合内部接口（v2.0 新增，4 个方法）。
 *
 * <p>契约来源：设计文档 §6 第 5 条。</p>
 *
 * <p>调用方：判题 / AI（校验作业时间窗与策略）、admin（班级统计）、监考大盘。</p>
 *
 * @author oj-cloud
 */
@FeignClient(name = "oj-teaching-service", contextId = "teachingAggregate", path = "/inner/teaching")
public interface TeachingAggregateFeignClient {

    /**
     * 取作业摘要（判题/AI 校验作业时间窗与 AI 策略）。
     *
     * @param assignmentId 作业 ID
     * @return 作业摘要
     */
    @GetMapping("/assignment/{assignmentId}")
    Result<AssignmentBriefDTO> getAssignment(@PathVariable("assignmentId") Long assignmentId);

    /**
     * 取考试运行时快照（权威剩余时间；admin 监考大盘复用）。
     *
     * @param examId 考试 ID
     * @return 运行时快照
     */
    @GetMapping("/exam/{examId}/runtime")
    Result<ExamRuntimeDTO> getExamRuntime(@PathVariable("examId") Long examId);

    /**
     * 取班级维度统计（admin 全站统计的班级维度）。
     *
     * @param classId 班级 ID
     * @param range   统计区间：7d / 30d / term / all
     * @return 班级统计
     */
    @GetMapping("/class/{classId}/stats")
    Result<ClassStatsDTO> getClassStats(@PathVariable("classId") Long classId,
                                        @RequestParam("range") String range);

    /**
     * 取班级学生 ID 列表（查重任务扇出用）。
     *
     * @param classId 班级 ID
     * @return 学生 ID 列表
     */
    @GetMapping("/class/{classId}/students/ids")
    Result<List<Long>> listClassStudentIds(@PathVariable("classId") Long classId);
}
