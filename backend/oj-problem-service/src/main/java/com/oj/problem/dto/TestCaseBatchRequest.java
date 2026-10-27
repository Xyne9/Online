package com.oj.problem.dto;

import lombok.Data;
import java.util.List;

@Data
public class TestCaseBatchRequest {
    private Integer version;
    private Integer totalScore;
    private List<TestCaseWriteRequest> items;
}
