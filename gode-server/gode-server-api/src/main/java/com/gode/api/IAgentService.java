package com.gode.api;

import com.gode.api.dto.AiAgentConfigResponseDTO;
import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.CreateSessionRequestDTO;
import com.gode.api.dto.CreateSessionResponseDTO;
import com.gode.api.response.Response;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    // Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatReActStream(@RequestBody ChatRequestDTO requestDTO);

}
