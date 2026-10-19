package com.gode.api.dto;
import lombok.Data;
import java.util.List;
@Data
public class ChatSessionDetailDTO {
    private ChatSessionDTO session;
    private List<ChatHistoryMessageDTO> messages;
    private boolean hasMore;
    private Long beforeMessageId;
}
