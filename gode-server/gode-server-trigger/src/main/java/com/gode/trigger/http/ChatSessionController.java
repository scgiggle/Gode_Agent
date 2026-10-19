package com.gode.trigger.http;

import com.gode.api.dto.*;
import com.gode.api.response.Response;
import com.gode.domain.agent.model.entity.ChatSessionEntity;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping(value = "/api/v1/chat", produces = "application/json")
@CrossOrigin(origins = "*")
public class ChatSessionController {
    @Resource private IChatSessionService chatSessionService;

    /** 当前用户/Agent 的会话列表，不绑定题目。 */
    @GetMapping("/sessions")
    public Response<ChatSessionPageDTO> list(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId,
            @RequestParam(value = "offset", defaultValue = "0") int offset, @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return execute(() -> {
            var page = chatSessionService.list(agentId, userId, offset, limit);
            ChatSessionPageDTO dto = new ChatSessionPageDTO();
            dto.setSessions(page.getSessions().stream().map(this::sessionDTO).toList());
            dto.setHasMore(page.isHasMore()); dto.setNextOffset(page.getNextOffset());
            return dto;
        });
    }

    /** 最近消息；beforeMessageId 可向前翻页，服务层先验证会话归属。 */
    @GetMapping("/session_detail")
    public Response<ChatSessionDetailDTO> detail(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId, @RequestParam(value = "beforeMessageId", required = false) Long beforeMessageId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return execute(() -> {
            var detail = chatSessionService.detail(agentId, userId, sessionId, beforeMessageId, limit);
            ChatSessionDetailDTO dto = new ChatSessionDetailDTO();
            dto.setSession(sessionDTO(detail.getSession()));
            dto.setMessages(detail.getMessages().stream().map(message -> {
                ChatHistoryMessageDTO item = new ChatHistoryMessageDTO();
                item.setId(message.getId()); item.setRole(message.getRole());
                item.setContent(message.getContent()); item.setCreatedAt(message.getCreatedAt());
                return item;
            }).toList());
            dto.setHasMore(detail.isHasMore()); dto.setBeforeMessageId(detail.getBeforeMessageId());
            return dto;
        });
    }

    /** 追加普通助手消息，不调用模型。 */
    @PostMapping("/message")
    public Response<ChatHistoryMessageDTO> appendMessage(@RequestBody ChatSessionRequestDTO request) {
        return execute(() -> {
            if (request == null) throw new AppException("0002", "请求不能为空");
            var message = chatSessionService.appendAssistantMessage(request.getAgentId(), request.getUserId(), request.getSessionId(), request.getContent());
            ChatHistoryMessageDTO dto = new ChatHistoryMessageDTO();
            dto.setId(message.getId()); dto.setRole(message.getRole()); dto.setContent(message.getContent());
            dto.setCreatedAt(message.getCreatedAt());
            return dto;
        });
    }

    /** 删除消息、里程碑和主会话；不删除长期记忆与错题。 */
    @PostMapping("/session_delete")
    public Response<Boolean> delete(@RequestBody ChatSessionRequestDTO request) {
        return execute(() -> {
            if (request == null) throw new AppException("0002", "请求不能为空");
            chatSessionService.deleteSession(request.getAgentId(), request.getUserId(), request.getSessionId());
            return true;
        });
    }

    private ChatSessionDTO sessionDTO(ChatSessionEntity session) {
        ChatSessionDTO dto = new ChatSessionDTO();
        dto.setSessionId(session.getId()); dto.setAgentId(session.getAgentId()); dto.setTitle(session.getTitle());
        dto.setMessageCount(session.getMessageCount()); dto.setCreatedAt(session.getCreatedAt()); dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }
    private <T> Response<T> execute(Supplier<T> action) {
        try {
            return Response.<T>builder().code("0000").info("成功").data(action.get()).build();
        } catch (AppException e) {
            return Response.<T>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("会话管理失败", e);
            return Response.<T>builder().code("0001").info("会话操作失败，请检查数据库连接后重试").build();
        }
    }
}
