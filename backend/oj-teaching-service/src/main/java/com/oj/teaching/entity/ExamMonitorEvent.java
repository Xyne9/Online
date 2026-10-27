package com.oj.teaching.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExamMonitorEvent {
    private Long id;
    private Long examId;
    private Long seq;
    private Long userId;
    private String eventType;
    private String tone;
    private String message;
    private String payload;
    private LocalDateTime createTime;
}
