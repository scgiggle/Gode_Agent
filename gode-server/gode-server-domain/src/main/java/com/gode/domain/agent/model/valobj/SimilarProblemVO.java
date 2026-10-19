package com.gode.domain.agent.model.valobj;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarProblemVO {

    private String title;
    private String titleSlug;
    private String difficulty;
    private String url;
    /** 力扣实际返回的知识点标签；缺失时不推测。 */
    private List<String> topicTags;
    /** 排序依据，供 Agent 如实解释，不是掌握率。 */
    private String recommendationReason;
    private List<String> matchedWeakTopics;
    /** 建议验证的错误模式，不表示题目必然具有这些边界。 */
    private List<String> practiceFocus;
}
