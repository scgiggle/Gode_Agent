package com.gode.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LongTermMemoryEntity {

    /** 自增主键 */
    private Long id;

    /** 用户 ID */
    private String userId;

    /** 来源会话 ID */
    private String sessionId;

    /** 学习记忆：USER_PREFERENCE / KNOWLEDGE_GAP / COMMON_MISTAKE / ALGORITHM_INSIGHT / SUBMISSION_CASE */
    private String memoryType;

    /** 记忆去重键（不同类型有不同生成策略，保证幂等） */
    private String memoryKey;

    /** 记忆内容（已截断，最长 300~500 字符） */
    private String content;

    /** 关键词集合（逗号分隔，用于召回时关键词重叠匹配） */
    private String keywords;

    /** 来源角色：user / tool / assistant */
    private String sourceRole;

    /** 置信度（0.0~1.0；用户自述与提交事实为 0.85，助手学习参考为 0.65） */
    private Double confidence;

    /** 命中/更新次数（同一记忆被重复提取时累加） */
    private Integer hitCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
