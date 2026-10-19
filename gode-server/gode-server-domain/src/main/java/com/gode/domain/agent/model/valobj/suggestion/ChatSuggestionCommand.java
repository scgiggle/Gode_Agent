package com.gode.domain.agent.model.valobj.suggestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 领域推荐输入，由 Controller 从请求 DTO 转换；不依赖 API 模块。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSuggestionCommand {
    private String agentId;
    private String userId;
    private String sessionId;
    private String problemSlug;
    private String problemTitle;
    private List<Message> messages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }
}
