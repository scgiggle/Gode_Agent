package com.gode.domain.agent.service.chat.session;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.model.entity.ChatSessionEntity;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.model.valobj.chat.ChatSessionDetailVO;
import com.gode.domain.agent.model.valobj.chat.ChatSessionPageVO;
import com.gode.domain.agent.service.IChatContextService;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.domain.agent.service.IPromptService;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import com.gode.domain.agent.service.intent.IntentService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 用户可拥有多个会话；只有长期记忆按用户共享，其余历史、任务态和缓存按会话隔离。 */
@Slf4j
@Service
public class ChatSessionService implements IChatSessionService {
    @Resource private DefaultArmoryFactory defaultArmoryFactory;
    @Resource private IChatHistoryRepository chatHistoryRepository;
    @Resource private ChatSessionActivity activity;
    @Resource private CustomAdkSessionService customAdkSessionService;
    @Resource private IChatContextService chatContextService;
    @Resource private IPromptService promptService;
    @Resource private IntentService intentService;

    /** 每次创建新 ID；数据库落库失败不能假装创建成功。 */
    @Override
    public String createSession(String agentId, String userId, String title) {
        agentId = required(agentId, "agentId");
        userId = required(userId, "userId");
        String sessionTitle = title == null || title.isBlank() ? "新对话" : title.trim();
        if (sessionTitle.length() > 200) throw invalid("会话标题不能超过200个字符");
        AiAgentRegisterVO register = agent(agentId);
        Session session = register.getRunner().sessionService().createSession(register.getAppName(), userId).blockingGet();
        try {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder().id(session.id())
                    .agentId(agentId).userId(userId).title(sessionTitle).messageCount(0).build());
        } catch (RuntimeException e) {
            register.getRunner().sessionService().deleteSession(register.getAppName(), userId, session.id())
                    .onErrorComplete().blockingAwait();
            throw e;
        }
        log.info("创建独立聊天会话 agentId={} userId={} sessionId={}", agentId, userId, session.id());
        return session.id();
    }

    /** 更新时间倒序分页；不按题目过滤。 */
    @Override
    public ChatSessionPageVO list(String agentId, String userId, int offset, int limit) {
        agentId = required(agentId, "agentId"); userId = required(userId, "userId");
        if (offset < 0 || offset > 1000000) throw invalid("offset不合法");
        int size = pageSize(limit);
        List<ChatSessionEntity> rows = chatHistoryRepository.querySessionPage(agentId, userId, offset, size + 1);
        boolean more = rows.size() > size;
        List<ChatSessionEntity> page = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new ChatSessionPageVO(page, more, offset + page.size());
    }

    /** 先校验归属，再查询向前翻页的用户/助手消息，并转换为时间正序。 */
    @Override
    public ChatSessionDetailVO detail(String agentId, String userId, String sessionId, Long beforeMessageId, int limit) {
        ChatSessionEntity session = owned(agentId, userId, sessionId);
        if (beforeMessageId != null && beforeMessageId <= 0) throw invalid("beforeMessageId不合法");
        int size = pageSize(limit);
        List<ChatMessageEntity> rows = chatHistoryRepository.queryVisibleMessagePage(session.getId(), beforeMessageId, size + 1);
        boolean more = rows.size() > size;
        List<ChatMessageEntity> messages = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        Collections.reverse(messages);
        Long before = messages.isEmpty() ? null : messages.get(0).getId();
        return new ChatSessionDetailVO(session, List.copyOf(messages), more, before);
    }

    /** 持有独占租约直至仓储事务提交及运行态清理完成，不影响用户长期记忆和错题。 */
    @Override
    public void deleteSession(String agentId, String userId, String sessionId) {
        ChatSessionEntity session = owned(agentId, userId, sessionId);
        try (ChatSessionActivity.Lease ignored = activity.acquire(session.getId())) {
            chatHistoryRepository.deleteOwnedSession(session.getAgentId(), session.getUserId(), session.getId());
            customAdkSessionService.removeUserSession(session.getUserId(), session.getId());
            chatContextService.clearSessionContext(session.getId());
            promptService.clearMilestones(session.getId());
            intentService.clearSession(session.getId());
        }
        log.info("删除聊天会话 agentId={} userId={} sessionId={}（保留长期记忆与错题）", agentId, userId, sessionId);
    }

    /** 复用普通助手消息的保存流程，不调用模型。 */
    @Override
    public ChatMessageEntity appendAssistantMessage(String agentId, String userId, String sessionId, String content) {
        owned(agentId, userId, sessionId);
        if (content == null || content.isBlank() || content.length() > 10000) throw invalid("消息不能为空或超过10000个字符");
        ChatMessageEntity message = ChatMessageEntity.builder().sessionId(sessionId).role("assistant")
                .content(content).priority("MEDIUM").tokenCount(content.length() / 2).build();
        chatHistoryRepository.saveMessage(message);
        return message;
    }

    /** 防止不同客户端同时运行同一 Session，且确保删除后的 ID 不能重新创建。 */
    @Override
    public ChatSessionActivity.Lease beginMessage(String agentId, String userId, String sessionId, String message) {
        required(sessionId, "sessionId");
        if (message == null || message.isBlank()) throw invalid("消息不能为空");
        ChatSessionActivity.Lease lease = activity.acquire(sessionId);
        try {
            ChatSessionEntity session = owned(agentId, userId, sessionId);
            restoreIfMissing(agent(session.getAgentId()), session);
            chatHistoryRepository.updateDefaultSessionTitle(sessionId, titleFromMessage(message));
            return lease;
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    /** 重启后只从该 Session 的数据库消息恢复近期文本，绝不从其他会话或用户级状态借历史。 */
    private void restoreIfMissing(AiAgentRegisterVO register, ChatSessionEntity metadata) {
        Runner runner = register.getRunner();
        Session existing = runner.sessionService().getSession(register.getAppName(), metadata.getUserId(),
                metadata.getId(), Optional.empty()).blockingGet();
        if (existing != null) return;
        List<ChatMessageEntity> history = chatHistoryRepository.getRecentMessages(metadata.getId(), 80);
        Session restored = runner.sessionService().createSession(register.getAppName(), metadata.getUserId(),
                new ConcurrentHashMap<>(), metadata.getId()).blockingGet();
        for (ChatMessageEntity message : history) {
            if (!List.of("user", "assistant").contains(message.getRole()) || message.getContent() == null) continue;
            boolean user = "user".equals(message.getRole());
            Event event = Event.builder().author(user ? "user" : runner.agent().name())
                    .invocationId("restored-" + metadata.getId())
                    .content(Content.builder().role(user ? "user" : "model")
                            .parts(List.of(Part.fromText(message.getContent()))).build()).build();
            runner.sessionService().appendEvent(restored, event).blockingGet();
        }
        log.info("恢复聊天会话 sessionId={} recentMessages={}", metadata.getId(), history.size());
    }

    private ChatSessionEntity owned(String agentId, String userId, String sessionId) {
        ChatSessionEntity session = chatHistoryRepository.queryOwnedSession(
                required(agentId, "agentId"), required(userId, "userId"), required(sessionId, "sessionId"));
        if (session == null) throw invalid("会话不存在或不属于当前用户和 Agent，请新建对话");
        return session;
    }
    private AiAgentRegisterVO agent(String agentId) {
        AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (register == null || register.getRunner() == null) throw invalid("Agent不存在或尚未装配");
        return register;
    }
    private String required(String text, String name) {
        if (text == null || text.isBlank() || text.length() > 64 || !text.equals(text.trim())) {
            throw invalid(name + "不能为空、包含首尾空白或超过64个字符");
        }
        return text;
    }
    private int pageSize(int limit) {
        if (limit < 1 || limit > 100) throw invalid("limit须为1到100");
        return limit;
    }
    private String titleFromMessage(String message) {
        int marker = message.lastIndexOf("用户问题：");
        String text = (marker >= 0 ? message.substring(marker + "用户问题：".length()) : message)
                .replaceAll("\\s+", " ").trim();
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }
    private AppException invalid(String info) { return new AppException("0002", info); }
}
