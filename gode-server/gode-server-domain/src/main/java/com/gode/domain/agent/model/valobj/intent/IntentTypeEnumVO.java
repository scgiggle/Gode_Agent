package com.gode.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum IntentTypeEnumVO {

    PROBLEM_ANALYSIS("题目分析"),
    HINT("分层提示"),
    CODE_REVIEW("代码检查与判题分析"),
    SOLUTION("完整解答"),
    COMPLEXITY("复杂度与优化"),
    SIMILAR_PROBLEM("相似题推荐"),
    NOTE("力扣笔记"),
    REVIEW("错题复盘与复习"),
    COMPOUND("复合指令"),
    EXPLAIN("解释说明"),
    SEARCH("搜索查找"),
    CHAT("闲聊"),
    CONTINUE("继续"),
    UNKNOWN("未知");

    private final String label;

}
