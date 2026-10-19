package com.gode.api;

import com.gode.api.dto.ChatSuggestionDTO;
import com.gode.api.dto.ChatSuggestionRequestDTO;
import com.gode.api.response.Response;

import java.util.List;

/** 推荐 HTTP 接口契约；Controller 的路由和实现后续接入。 */
public interface IChatSuggestionService {
    Response<List<ChatSuggestionDTO>> suggest(ChatSuggestionRequestDTO requestDTO);
}
