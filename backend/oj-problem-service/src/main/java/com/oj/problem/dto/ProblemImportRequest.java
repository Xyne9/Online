package com.oj.problem.dto;

import lombok.Data;
import java.util.List;

@Data
public class ProblemImportRequest {
    private String format;
    private Boolean dryRun;
    private List<ProblemWriteRequest> items;
}
