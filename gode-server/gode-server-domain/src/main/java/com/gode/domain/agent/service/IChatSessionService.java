package com.gode.domain.agent.service;
import com.gode.domain.agent.model.valobj.chat.ChatSessionDetailVO;
import com.gode.domain.agent.model.valobj.chat.ChatSessionPageVO;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.service.chat.session.ChatSessionActivity;
public interface IChatSessionService {
    String createSession(String agentId, String userId, String title);
    ChatSessionPageVO list(String agentId, String userId, int offset, int limit);
    ChatSessionDetailVO detail(String agentId, String userId, String sessionId, Long beforeMessageId, int limit);
    void deleteSession(String agentId, String userId, String sessionId);
    ChatMessageEntity appendAssistantMessage(String agentId, String userId, String sessionId, String content);
    /** 校验归属、恢复当前 Session 并占用运行租约；调用方必须在实际执行结束时 close。 */
    ChatSessionActivity.Lease beginMessage(String agentId, String userId, String sessionId, String message);
}
