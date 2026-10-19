package com.gode.domain.agent.model.valobj.chat;
import com.gode.domain.agent.model.entity.ChatSessionEntity;
import lombok.Value;
import java.util.List;
@Value
public class ChatSessionPageVO {
    List<ChatSessionEntity> sessions;
    boolean hasMore;
    int nextOffset;
}
