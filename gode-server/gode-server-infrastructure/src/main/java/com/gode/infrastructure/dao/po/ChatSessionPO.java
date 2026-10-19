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
public class ChatSessionPO {

    /** 会话 ID（ADK Session.id()，UUID，主键） */
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 消息数量（每次保存消息时 +1） */
    private Integer messageCount;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

    /** 更新时间（DB 默认 ON UPDATE CURRENT_TIMESTAMP） */
    private LocalDateTime updatedAt;

}
