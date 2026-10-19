package com.gode.domain.agent.service;

import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

public interface ILongTermMemoryService {

    /**
     * 记录用户明确的长期学习偏好（USER_PREFERENCE）、自述薄弱点（KNOWLEDGE_GAP）
     * 和常见错误（COMMON_MISTAKE）。
     * <p>
     * 仅明确的学习场景长期表达入库；不将本轮提示要求或代码中的词语作为长期偏好。
     * 意图标签会拼入关键词，提高后续召回命中率。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * 查询与当前对话相关的长期记忆（粗筛+精排两阶段召回）。
     * <p>
     * 阶段一：从 DB 拉最近 120 条记忆（粗筛）；
     * 阶段二：用查询关键词与记忆关键词做重叠打分（精排），返回 Top-N。
     *
     * @param userId 用户 ID
     * @param query  召回查询（首条+最近用户消息拼接）
     * @param limit  最大返回条数
     * @return 相关记忆列表（按打分降序）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * 构建长期记忆摘要字符串，用于注入 Prompt 的 [长期记忆] 段落。
     * <p>
     * 输出格式示例：
     * <pre>
     * - [USER_PREFERENCE] 以后默认用 Java，先给提示
     * - [KNOWLEDGE_GAP] 我还不熟悉动态规划的状态转移
     * </pre>
     *
     * @param userId 用户 ID
     * @param query  召回查询
     * @param limit  最大返回条数
     * @return 记忆摘要字符串，无记忆时返回空字符串
     */
    String buildMemorySummary(String userId, String query, int limit);

    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    void saveAssistantMessage(String userId, String sessionId, String assistantContent);

    void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound);

    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success);

    /** 记录提交工具返回的结构化判题状态；不把工具读取失败视为算法错误。 */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

}
