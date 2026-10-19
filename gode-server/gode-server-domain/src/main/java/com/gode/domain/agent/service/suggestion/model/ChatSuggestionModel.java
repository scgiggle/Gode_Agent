package com.gode.domain.agent.service.suggestion.model;

/** 推荐模型边界，便于替换模型实现以及在测试中模拟输出。 */
public interface ChatSuggestionModel {
    String generate(String agentId, String instruction, String contextJson);
}
