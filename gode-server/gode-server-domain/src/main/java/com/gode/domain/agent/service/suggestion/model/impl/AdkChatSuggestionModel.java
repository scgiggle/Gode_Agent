package com.gode.domain.agent.service.suggestion.model.impl;

import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.suggestion.model.ChatSuggestionModel;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
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
import jakarta.annotation.Resource;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AdkChatSuggestionModel implements ChatSuggestionModel {
    @Resource
    private ApplicationContext applicationContext;

    /** 可选配置，默认只等待 20 秒；不沿用主对话较长的超时。 */
    @Value("${ai.agent.suggestion.timeout-seconds:20}")
    private long timeoutSeconds = 20;

    /** 仅复用所选 Agent 的连接及模型名，每次创建独立、无工具的推荐 Runner。 */
    @Override
    public String generate(String agentId, String instruction, String contextJson) {
        if (agentId == null || agentId.isBlank() || !applicationContext.containsBean(agentId)
                || !applicationContext.isTypeMatch(agentId, AiAgentRegisterVO.class)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "指定的 Agent 不存在");
        }
        AiAgentRegisterVO agent = applicationContext.getBean(agentId, AiAgentRegisterVO.class);
        if (agent.getOpenAiApi() == null || agent.getChatModelName() == null || agent.getChatModelName().isBlank()) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "当前 Agent 的模型配置不完整");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalStateException("推荐模型超时配置必须为 1～60 秒");
        }
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(agent.getOpenAiApi())
                .defaultOptions(OpenAiChatOptions.builder().model(agent.getChatModelName()).build())
                .build();
        LlmAgent suggestionAgent = LlmAgent.builder()
                .name("ChatSuggestionAgent")
                .model(new SpringAI(model))
                .instruction(instruction)
                .build();
        Runner runner = Runner.builder()
                .agent(suggestionAgent)
                .appName("chat-suggestion")
                .artifactService(new InMemoryArtifactService())
                .sessionService(new InMemorySessionService())
                .memoryService(new InMemoryMemoryService())
                .plugins(List.of())
                .build();
        RunConfig runConfig = RunConfig.builder().autoCreateSession(true)
                .streamingMode(RunConfig.StreamingMode.NONE).build();
        List<Event> events = runner.runAsync(
                        "chat-suggestion-user", "suggestion-" + UUID.randomUUID(),
                        Content.fromParts(Part.fromText(contextJson)), runConfig)
                // SpringAI 的非流式 call 会同步阻塞；先调度订阅，timeout 才能及时释放 HTTP 线程。
                .toList().subscribeOn(Schedulers.io())
                .timeout(timeoutSeconds, TimeUnit.SECONDS).blockingGet();
        // 只取最终回答的普通文本，不拼接思考内容、工具内容和中间回答。
        return events.stream().filter(Event::finalResponse)
                .map(event -> event.content().flatMap(Content::parts)
                        .map(parts -> parts.stream().filter(part -> !part.thought().orElse(false))
                                .map(part -> part.text().orElse("")).reduce("", String::concat))
                        .orElse(""))
                .filter(text -> !text.isBlank())
                .reduce((previous, latest) -> latest)
                .orElseThrow(() -> new IllegalStateException("推荐 Runner 未返回最终文本"));
    }
}
