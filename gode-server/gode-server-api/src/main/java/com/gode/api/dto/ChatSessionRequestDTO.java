package com.gode.api.dto;
import lombok.Data;
/** 会话操作请求；userId 仍沿用插件身份，接入登录后应从认证上下文获取。 */
@Data
public class ChatSessionRequestDTO {
    private String agentId;
    private String userId;
    private String sessionId;
    private String content;
}
