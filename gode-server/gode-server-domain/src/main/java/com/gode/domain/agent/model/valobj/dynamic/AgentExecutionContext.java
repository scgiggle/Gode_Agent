package com.gode.domain.agent.model.valobj.dynamic;


import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentExecutionContext {

    /**
     * 发起对话的用户 ID
     */
    String userId;

    /**
     * 发起派发的父 Agent ID（即 ADK 中的 agentName）
     */
    String agentId;

    /**
     * 父会话 ID（invocationId），用于关联子 Agent 会话与父会话
     */
    String parentSessionId;

    /**
     * 当前执行的任务 ID（预留）
     */
    String taskId;

}
