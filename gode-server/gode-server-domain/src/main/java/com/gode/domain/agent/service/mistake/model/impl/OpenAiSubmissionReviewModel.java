package com.gode.domain.agent.service.mistake.model.impl;

import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.mistake.model.SubmissionReviewModel;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OpenAiSubmissionReviewModel implements SubmissionReviewModel {
    @Resource
    private ApplicationContext applicationContext;

    /** 复用所选 Agent 的 API 和模型名，通过独立、无工具的 ADK Runner 生成复盘。 */
    @Override
    public String analyze(String agentId, String systemInstruction, String submissionJson) {
        if (!applicationContext.containsBean(agentId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "指定的 Agent 不存在");
        }
        AiAgentRegisterVO agent = applicationContext.getBean(agentId, AiAgentRegisterVO.class);
        if (agent.getOpenAiApi() == null || agent.getChatModelName() == null
                || agent.getChatModelName().isBlank()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "当前 Agent 的模型配置不完整");
        }
        // 每次调用独立模型，不修改意图识别器或父 Agent 的共享配置，也不注册执行工具。
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(agent.getOpenAiApi())
                .defaultOptions(OpenAiChatOptions.builder().model(agent.getChatModelName()).build())
                .build();
        LlmAgent reviewAgent = LlmAgent.builder()
                .name("SubmissionReviewAgent")
                .model(new SpringAI(model))
                .instruction(systemInstruction)
                .build();
        Runner runner = Runner.builder()
                .agent(reviewAgent)
                .appName("submission-review")
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .plugins(List.of())
                .build();
        RunConfig runConfig = RunConfig.builder()
                .autoCreateSession(true)
                .streamingMode(RunConfig.StreamingMode.NONE)
                .build();
        List<Event> events = runner.runAsync(
                        "submission-review-user", "review-" + UUID.randomUUID(),
                        Content.fromParts(Part.fromText(submissionJson)), runConfig)
                .toList()
                .timeout(90, TimeUnit.SECONDS)
                .blockingGet();
        // 仅取最终回答中的文本；不把中间事件、工具事件或思考内容拼进 JSON 草稿。
        return events.stream()
                .filter(Event::finalResponse)
                .map(event -> event.content().flatMap(Content::parts)
                        .map(parts -> parts.stream()
                                .filter(part -> !part.thought().orElse(false))
                                .map(part -> part.text().orElse(""))
                                .reduce("", String::concat))
                        .orElse(""))
                .filter(text -> !text.isBlank())
                .reduce((previous, latest) -> latest)
                .orElseThrow(() -> new IllegalStateException("复盘 Runner 未返回最终文本"));
    }
}
