package com.oj.problem.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
import java.util.Map;

/** 创建/局部更新请求；未提供的字段保持原值。 */
@Data
public class ProblemWriteRequest {
    private Integer version;
    private String title;
    private Integer difficulty;
    private Long knowledgePointId;
    @JsonIgnore
    private boolean knowledgePointProvided;
    private List<String> tags;
    private Integer timeLimitMs;
    private Integer memoryLimitMb;
    private Boolean aiEnabled;
    private Integer aiQuotaLimit;
    private List<Integer> aiStages;
    private String statement;
    private String officialSolution;
    private Map<String, String> codeTemplates;
    private List<TestCaseWriteRequest> testCases;

    @JsonSetter("knowledgePointId")
    public void setKnowledgePointId(Long knowledgePointId) {
        this.knowledgePointId = knowledgePointId;
        this.knowledgePointProvided = true;
    }
}
