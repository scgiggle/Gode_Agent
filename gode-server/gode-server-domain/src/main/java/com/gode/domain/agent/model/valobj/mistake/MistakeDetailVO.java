package com.gode.domain.agent.model.valobj.mistake;

import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MistakeDetailVO {
    private LeetcodeMistakeEntity mistake;
    private LeetcodeMistakeReviewEntity latestReview;
    /** 全部复盘，按创建时间、ID 倒序，包含最新复盘。 */
    @Builder.Default
    private List<LeetcodeMistakeReviewEntity> reviews = List.of();
}
