package com.oj.common.api.dto.judge;

/** AI 胶囊解锁态；严格按用户、题目、教学活动三键隔离。 */
public record AiSubmissionStateDTO(boolean hasFailed, boolean hasAc) { }
