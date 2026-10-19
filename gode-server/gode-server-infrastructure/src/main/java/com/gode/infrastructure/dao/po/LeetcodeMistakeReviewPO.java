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
public class LeetcodeMistakeReviewPO {

    private Long id;
    private Long mistakeId;
    private String failReason;
    private String failCase;
    private String aiAnalysis ;
    private String actionPlan;
    private String sourceSessionId;
    private LocalDateTime createdAt;
}
