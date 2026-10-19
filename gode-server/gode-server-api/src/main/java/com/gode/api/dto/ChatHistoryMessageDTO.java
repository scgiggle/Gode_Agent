package com.gode.api.dto;
import lombok.Data;
import java.time.LocalDateTime;
@Data
public class ChatHistoryMessageDTO {
    private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
