package com.gode.api.dto;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class ChatSessionDTO {
    private String sessionId;
    private String agentId;
    private String title;
    private Integer messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
