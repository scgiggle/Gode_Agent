package com.gode.trigger.http;

import com.gode.api.dto.ChatSuggestionDTO;
import com.gode.api.dto.ChatSuggestionRequestDTO;
import com.gode.api.response.Response;
import com.gode.domain.agent.model.valobj.suggestion.ChatSuggestionCommand;
import com.gode.domain.agent.service.IChatSuggestionService;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 下一步建议是独立请求，不影响主对话的输出或会话状态。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class ChatSuggestionController implements com.gode.api.IChatSuggestionService {
    @Resource
    private IChatSuggestionService chatSuggestionService;

    @Override
    @PostMapping(value = "/suggestions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Response<List<ChatSuggestionDTO>> suggest(@RequestBody ChatSuggestionRequestDTO request) {
        try {
            if (request == null) throw new AppException("0002", "请求不能为空");
            ChatSuggestionCommand command = ChatSuggestionCommand.builder()
                    .agentId(request.getAgentId()).userId(request.getUserId())
                    .sessionId(request.getSessionId()).problemSlug(request.getProblemSlug())
                    .problemTitle(request.getProblemTitle())
                    .messages(request.getMessages() == null ? null : request.getMessages().stream()
                            .map(message -> message == null ? null : ChatSuggestionCommand.Message.builder()
                                    .role(message.getRole()).content(message.getContent()).build()).toList())
                    .build();
            List<ChatSuggestionDTO> data = chatSuggestionService.suggest(command).stream().map(value -> {
                ChatSuggestionDTO dto = new ChatSuggestionDTO();
                dto.setLabel(value.getLabel());
                dto.setPrompt(value.getPrompt());
                return dto;
            }).toList();
            return Response.<List<ChatSuggestionDTO>>builder().code("0000").info("成功").data(data).build();
        } catch (AppException exception) {
            return Response.<List<ChatSuggestionDTO>>builder()
                    .code(exception.getCode()).info(exception.getInfo()).build();
        } catch (Exception exception) {
            log.warn("下一步推荐接口失败 errorType={}", exception.getClass().getSimpleName());
            return Response.<List<ChatSuggestionDTO>>builder()
                    .code("0001").info("下一步推荐暂不可用，请稍后重试").build();
        }
    }
}
