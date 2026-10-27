package com.oj.problem.dto;

import lombok.Data;

/** 单点与批量共用；批量已有点可以省略正文，以保留磁盘中的完整大文件。 */
@Data
public class TestCaseWriteRequest {
    private Long id;
    private Integer seqNo;
    private String kind;
    private String input;
    private String output;
    private Integer score;
    private Boolean isPublic;
}
