package com.gode.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次错题复盘；同一道错题可以关联多条历史记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeetcodeMistakeReviewEntity {

    private Long id;
    private Long mistakeId;
    private String failReason;
    private String failCase;
    private String aiAnalysis;
    private String actionPlan;
    private String sourceSessionId;
    private LocalDateTime createdAt;
}
