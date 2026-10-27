package com.oj.ai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_chat_record")
public class AiChatRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private Long userId;
    private Long problemId;
    private Long assignmentId;
    private Long submissionId;
    private String traceId;
    private Integer stageType;
    private Integer hintLevel;
    private String userMessage;
    private String numberedCode;
    private String selectedChannel;
    private String actualModel;
    private Integer isFallback;
    private String toolCalled;
    private String aiResponse;
    private Integer guardrailAction;
    private String guardReason;
    private String guardReplacement;
    private Integer ttftMs;
    private Integer tokensIn;
    private Integer tokensOut;
    private Integer costCents;
    private LocalDateTime createTime;
}
