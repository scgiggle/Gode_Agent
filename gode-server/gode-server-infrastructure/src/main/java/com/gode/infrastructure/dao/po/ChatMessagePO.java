package com.gode.infrastructure.dao.po;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagePO {

    /** 自增主键 */
    private Long id;

    /** 会话 ID（ADK Session.id()） */
    private String sessionId;

    /** 消息角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（仅 role=tool 时有值） */
    private String toolName;

    /** 工具调用 ID（仅 role=tool 时有值） */
    private String toolCallId;

    /** 优先级：CRITICAL / HIGH / MEDIUM / LOW */
    private String priority;

    /** 预估 token 数 */
    private Integer tokenCount;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

}
