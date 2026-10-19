package com.gode.domain.agent.model.valobj.suggestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/** 校验后的只读推荐项，与聊天消息和用户真实意图区分。 */
@Value
@Builder
@AllArgsConstructor
public class ChatSuggestionVO {
    String label;
    String prompt;
}
