package com.gode.api.dto;
import lombok.Data;
import java.util.List;
@Data
public class ChatSessionPageDTO {
    private List<ChatSessionDTO> sessions;
    private boolean hasMore;
    private int nextOffset;
}
