package com.gode.domain.agent.service;

import com.gode.domain.agent.model.valobj.prompt.PromptContextVO;

import java.util.List;
import java.util.Map;

public interface IChatContextService {
    /**
     * 聚合上下文，构建 PromptContextVO
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param messageHistory    消息历史
     * @return 组装完成的动态上下文值对象
     */
    PromptContextVO buildPromptContext(
            String sessionId,
            String userId,
            List<Map<String, Object>> messageHistory
    );

    /**
     * 在 token 预算内裁剪消息历史
     *
     * @param history     原始消息历史
     * @param tokenBudget token 预算（<=0 时使用默认值）
     * @return 裁剪后的消息历史
     */
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);

    /**
     * 记录工具执行结果（供生成工具摘要）
     *
     * @param sessionId 对话会话 ID
     * @param toolName  工具名称
     * @param result    执行结果
     */
    void pushToolResult(String sessionId, String toolName, String result);

    /**
     * 清理指定会话的上下文缓存
     *
     * @param sessionId 会话 ID
     */
    void clearSessionContext(String sessionId);
}
