package com.gode.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户对一道力扣错题的当前学习状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeetcodeMistakeEntity {

    private Long id;
    private String userId;
    /** 力扣题号，例如 1、3、15。 */
    private String problemId;
    /** 力扣 URL slug，例如 two-sum。 */
    private String problemSlug;
    private String title;
    private String difficulty;
    private String errorSummary;
    /** 逗号分隔的标签，例如：哈希表,数组。 */
    private String tags;
    /** 待复习 / 复习中 / 已掌握。 */
    private String status;
    private LocalDateTime nextReviewTime;
    private Integer reviewCount;
    private String sourceSessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
