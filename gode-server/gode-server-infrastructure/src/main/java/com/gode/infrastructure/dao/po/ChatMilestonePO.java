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
public class ChatMilestonePO {

    /** 自增主键 */
    private Long id;

    /** 会话 ID */
    private String sessionId;

    /** 里程碑类型：TASK_CHANGE / TASK_COMPLETE / USER_CORRECTION / ERROR 等（对应 MilestoneVO.Type 枚举） */
    private String type;

    /** 内容摘要 */
    private String content;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

}
