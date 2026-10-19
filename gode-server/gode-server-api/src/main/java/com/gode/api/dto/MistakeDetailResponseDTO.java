package com.gode.api.dto;

import lombok.Data;

import java.util.List;

/** 错题详情及完整复盘历史，保留 latestReview 兼容旧客户端。 */
@Data
public class MistakeDetailResponseDTO {
    private LeetcodeMistakeDTO mistake;
    private LeetcodeMistakeReviewDTO latestReview;
    /** 按创建时间、ID 倒序，包含最新复盘；没有记录时返回空数组。 */
    private List<LeetcodeMistakeReviewDTO> reviews = List.of();
}
