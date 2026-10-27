package com.oj.ai.dto;

import jakarta.validation.constraints.*;

public record ChatRequest(@NotNull @Positive Long problemId,
                          @NotNull @PositiveOrZero Long assignmentId,
                          @Size(max = 32) String selectedChannelCode,
                          @NotNull @Min(1) @Max(4) Integer capsuleStage,
                          @NotBlank @Size(max = 4000) String userMessage,
                          @Size(max = 30000) String currentCode) { }
