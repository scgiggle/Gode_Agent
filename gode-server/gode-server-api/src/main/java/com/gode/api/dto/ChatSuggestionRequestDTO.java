package com.gode.api.dto;

import lombok.Data;

import java.util.List;

/** 下一步问题推荐请求；对话内容来自客户端，不读取或改写聊天会话。 */
@Data
public class ChatSuggestionRequestDTO {
    private String agentId;
    private String userId;
    private String sessionId;
    private String problemSlug;
    private String problemTitle;
    private List<MessageDTO> messages;

    /** 对话消息只传递角色和文本，不传工具权限或模型凭据。 */
    @Data
    public static class MessageDTO {
        private String role;
        private String content;
    }
}
