package com.oj.common.api.dto.judge;

import lombok.Data;
import java.io.Serializable;

/** 教务提交已封存的考试草稿；内部接口可在考试截止后完成自动收卷。 */
@Data
public class ExamJudgeRequestDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private Long assignmentId;
    private Long problemId;
    private String language;
    private String code;
}
