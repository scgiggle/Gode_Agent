package com.gode.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次错题复盘的接口展示对象。 */
@Data
public class LeetcodeMistakeReviewDTO {
    private Long id;
    private Long mistakeId;
    private String failReason;
    private String failCase;
    private String aiAnalysis;
    private String actionPlan;
    private String sourceSessionId;
    private LocalDateTime createdAt;
}
