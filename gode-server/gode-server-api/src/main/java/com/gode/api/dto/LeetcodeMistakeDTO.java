package com.gode.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 错题主记录的接口展示对象。 */
@Data
public class LeetcodeMistakeDTO {
    private Long id;
    private String userId;
    private String problemId;
    private String problemSlug;
    private String title;
    private String difficulty;
    private String errorSummary;
    private String tags;
    private String status;
    private LocalDateTime nextReviewTime;
    private Integer reviewCount;
    private String sourceSessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
