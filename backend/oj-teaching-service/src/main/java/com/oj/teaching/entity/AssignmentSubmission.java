package com.oj.teaching.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("assignment_submission")
public class AssignmentSubmission {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String displayNo;
    private Long assignmentId;
    private Long userId;
    private Long classId;
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime undoDeadlineAt;
    private LocalDateTime revokedAt;
    private Integer problemCount;
    private Integer answeredCount;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
