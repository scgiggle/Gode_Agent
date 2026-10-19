package com.gode.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.openai.api.OpenAiApi;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;

    /**
     * 智能体执行对象
     */
    private Runner runner;

    /**
     * 智能体的 LLM API（与 Runner 共用同一套配置，
     * 供意图识别等旁路能力构建独立 ChatModel，避免单独配置模型）
     */
    private OpenAiApi openAiApi;

    /**
     * 智能体配置的模型名称（供意图识别复用）
     */
    private String chatModelName;

}
