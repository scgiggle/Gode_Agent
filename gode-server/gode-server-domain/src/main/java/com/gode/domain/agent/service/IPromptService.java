package com.gode.domain.agent.service;

import java.util.List;
import java.util.Map;

/**
 * 动态提示词服务。
 */
public interface IPromptService {

    /**
     * 构建包含动态上下文的用户消息。
     */
    String buildEnrichedMessage(
            String userMessage,
            String sessionId,
            String userId,
            List<String> recentCommands,
            List<Map<String, Object>> messageHistory
    );

    /**
     * 构建包含动态上下文和意图标签的用户消息。
     */
    String buildEnrichedMessage(
            String userMessage,
            String sessionId,
            String userId,
            List<String> recentCommands,
            List<Map<String, Object>> messageHistory,
            String intentLabel
    );

    /**
     * 检测并记录里程碑事件。
     */
    void detectAndRecordMilestone(String sessionId, String role, String content);

    /**
     * 清理指定会话的里程碑缓存。
     */
    void clearMilestones(String sessionId);
}
