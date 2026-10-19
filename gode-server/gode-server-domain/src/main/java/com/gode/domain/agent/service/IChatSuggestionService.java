package com.gode.domain.agent.service;

import com.gode.domain.agent.model.valobj.suggestion.ChatSuggestionCommand;
import com.gode.domain.agent.model.valobj.suggestion.ChatSuggestionVO;

import java.util.List;

public interface IChatSuggestionService {
    /** 生成 1～3 个下一步问题，不写数据库、不修改当前 Runner 或会话。 */
    List<ChatSuggestionVO> suggest(ChatSuggestionCommand command);
}
