package com.gode.domain.agent.model.valobj.chat;
import com.gode.domain.agent.model.entity.ChatSessionEntity;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import lombok.Value;
import java.util.List;
/** 历史仅展示用户/助手文本；工具原始数据不直接暴露给历史页面。 */
@Value
public class ChatSessionDetailVO {
    ChatSessionEntity session;
    List<ChatMessageEntity> messages;
    boolean hasMore;
    Long beforeMessageId;
}
