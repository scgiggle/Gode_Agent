package com.gode.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEntity {

    /** 自增主键 */
    private Long id;

    /** 会话 ID（ADK Session.id()） */
    private String sessionId;

    /** 消息角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（仅 role=tool 时有值，如 "executeCommand"） */
    private String toolName;

    /** 工具调用 ID（仅 role=tool 时有值，用于关联 assistant 的 tool_call） */
    private String toolCallId;

    /** 优先级：CRITICAL / HIGH / MEDIUM / LOW，工具结果默认 HIGH */
    private String priority;

    /** 预估 token 数（≈ content.length() / 2） */
    private Integer tokenCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

}
