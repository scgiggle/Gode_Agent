package com.gode.infrastructure.dao.adapter.repository;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.model.entity.ChatSessionEntity;
import com.gode.domain.agent.model.valobj.prompt.MilestoneVO;
import com.gode.infrastructure.dao.IChatMessageDao;
import com.gode.infrastructure.dao.IChatMilestoneDao;
import com.gode.infrastructure.dao.IChatSessionDao;
import com.gode.infrastructure.dao.po.ChatMessagePO;
import com.gode.infrastructure.dao.po.ChatMilestonePO;
import com.gode.infrastructure.dao.po.ChatSessionPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.gode.types.exception.AppException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ChatHistoryRepository implements IChatHistoryRepository {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private IChatMilestoneDao chatMilestoneDao;

    @Override
    public void saveSession(ChatSessionEntity session) {
        ChatSessionPO po = ChatSessionPO.builder()
                .id(session.getId())
                .agentId(session.getAgentId())
                .userId(session.getUserId())
                .title(session.getTitle())
                .messageCount(session.getMessageCount())
                .build();
        chatSessionDao.insert(po);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMessage(ChatMessageEntity message) {
        ChatMessagePO po = ChatMessagePO.builder()
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .toolName(message.getToolName())
                .toolCallId(message.getToolCallId())
                .priority(message.getPriority())
                .tokenCount(message.getTokenCount())
                .build();
        chatMessageDao.insert(po);
        chatSessionDao.updateMessageCount(message.getSessionId());
        message.setId(po.getId());
    }

    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        List<ChatMessagePO> pos = chatMessageDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessagePO> reversedPos = new ArrayList<>(pos);
        Collections.reverse(reversedPos);
        return reversedPos.stream().map(this::toMessageEntity).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget) {
        List<ChatMessageEntity> recent = getRecentMessages(sessionId, 100);
        if (recent.isEmpty() || tokenBudget <= 0) {
            return recent;
        }

        List<ChatMessageEntity> result = new ArrayList<>();
        int currentTokens = 0;
        // 从最近的消息开始往前累积
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageEntity msg = recent.get(i);
            int tokens = msg.getTokenCount() != null ? msg.getTokenCount() : 0;
            if (currentTokens + tokens > tokenBudget && !result.isEmpty()) {
                break;
            }
            result.add(0, msg);
            currentTokens += tokens;
        }

        return result;
    }

    @Override
    public void saveMilestone(String sessionId, MilestoneVO milestoneVO) {
        ChatMilestonePO po = ChatMilestonePO.builder()
                .sessionId(sessionId)
                .type(milestoneVO.getType().name())
                .content(milestoneVO.getContent())
                .build();
        chatMilestoneDao.insert(po);
    }

    @Override
    public List<MilestoneVO> getRecentMilestones(String sessionId, int limit) {
        List<ChatMilestonePO> pos = chatMilestoneDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(po -> MilestoneVO.builder()
                .type(MilestoneVO.Type.valueOf(po.getType()))
                .content(po.getContent())
                .timestamp(po.getCreatedAt() != null ? Timestamp.valueOf(po.getCreatedAt()).getTime() : System.currentTimeMillis())
                .build()).collect(Collectors.toList());
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        List<ChatSessionPO> pos = chatSessionDao.querySessionList(agentId, userId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(this::toSessionEntity).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return getRecentMessages(sessionId, limit);
    }

    // ==================== PO ↔ Entity 转换 ====================

    @Override
    public ChatSessionEntity queryOwnedSession(String agentId, String userId, String sessionId) {
        ChatSessionPO po = chatSessionDao.queryOwnedSession(agentId, userId, sessionId);
        return po == null ? null : toSessionEntity(po);
    }

    @Override
    public List<ChatSessionEntity> querySessionPage(String agentId, String userId, int offset, int limit) {
        return chatSessionDao.querySessionPage(agentId, userId, offset, limit).stream()
                .map(this::toSessionEntity).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> queryVisibleMessagePage(String sessionId, Long beforeMessageId, int limit) {
        return chatMessageDao.queryVisibleMessagePage(sessionId, beforeMessageId, limit).stream()
                .map(this::toMessageEntity).collect(Collectors.toList());
    }

    @Override
    public void updateDefaultSessionTitle(String sessionId, String title) {
        chatSessionDao.updateDefaultTitle(sessionId, title);
    }

    /** 先锁定归属记录；子记录与主记录要么全部删除，要么全部回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOwnedSession(String agentId, String userId, String sessionId) {
        if (chatSessionDao.lockOwnedSession(agentId, userId, sessionId) == null) {
            throw new AppException("0002", "会话不存在或不属于当前用户和 Agent");
        }
        chatMessageDao.deleteBySessionId(sessionId);
        chatMilestoneDao.deleteBySessionId(sessionId);
        if (chatSessionDao.deleteOwnedSession(agentId, userId, sessionId) != 1) {
            throw new AppException("0002", "会话删除失败，请刷新后重试");
        }
    }

    private ChatSessionEntity toSessionEntity(ChatSessionPO po) {
        return ChatSessionEntity.builder()
                .id(po.getId())
                .agentId(po.getAgentId())
                .userId(po.getUserId())
                .title(po.getTitle())
                .messageCount(po.getMessageCount())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private ChatMessageEntity toMessageEntity(ChatMessagePO po) {
        return ChatMessageEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .role(po.getRole())
                .content(po.getContent())
                .toolName(po.getToolName())
                .toolCallId(po.getToolCallId())
                .priority(po.getPriority())
                .tokenCount(po.getTokenCount())
                .createdAt(po.getCreatedAt())
                .build();
    }
}
