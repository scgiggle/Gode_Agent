package com.gode.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeetcodeMistakePO {

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
    private int reviewCount;
    private String sourceSessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

}
