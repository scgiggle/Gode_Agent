package com.gode.domain.agent.model.valobj.recommend;

import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import java.util.List;

/** 用户级学习记录的只读统计；疑似薄弱点不是能力评分或准确掌握率。 */
public record LearningRecommendationVO(List<SimilarProblemVO> recommendations, boolean personalized,
        String learningDataStatus, String message, int sampledProblemCount, int sampledReviewCount,
        int excludedMasteredCount, List<WeakTopic> weakTopics, List<ErrorPattern> errorPatterns) {
    public record WeakTopic(String topic, int problemCount, int reviewCount, List<String> evidenceProblemSlugs) { }
    public record ErrorPattern(String name, int problemCount, int reviewCount, List<String> evidenceProblemSlugs) { }
}
