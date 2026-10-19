package com.gode.cases.react.node;

import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.react.AbstractAIAgentReActSupport;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.service.ILongTermMemoryService;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;

    @Override
    protected ReActResultDTO doApply(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        log.info("ReAct RootNode - 初始化上下文");

        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String message = requestParameter.getMessage();

        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);
        dynamicContext.setOriginalUserTask(message);

        // 从数据库恢复最近的历史消息。
        List<Map<String, Object>> history = new ArrayList<>();
        List<ChatMessageEntity> recentMessages =
                longTermMemoryService.getRecentMessages(sessionId, 50);

        for (ChatMessageEntity chatMessage : recentMessages) {
            Map<String, Object> messageMap = new HashMap<>();
            messageMap.put("role", chatMessage.getRole());
            messageMap.put(
                    "content",
                    chatMessage.getContent() != null ? chatMessage.getContent() : ""
            );

            if ("tool".equals(chatMessage.getRole())
                    && chatMessage.getToolCallId() != null) {
                messageMap.put("tool_call_id", chatMessage.getToolCallId());
                messageMap.put("name", chatMessage.getToolName());
            }

            history.add(messageMap);
        }

        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentToolCalls(new ArrayList<>());
        dynamicContext.setCurrentToolResults(new ArrayList<>());
        dynamicContext.setCurrentStep(new AtomicInteger(0));
        dynamicContext.setMaxSteps(DEFAULT_MAX_STEPS);
        dynamicContext.setMaxToolCalls(DEFAULT_MAX_TOOL_CALLS);
        dynamicContext.setMaxToolCallsPerRound(DEFAULT_MAX_TOOL_CALLS_PER_ROUND);

        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        dynamicContext.appendUserMessage(message);

        log.info(
                "ReAct RootNode - 初始化完成 sessionId={}, userId={}, agentId={}",
                sessionId,
                userId,
                agentId
        );

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<
            ChatRequestDTO,
            DefaultReActFactory.DynamicContext,
            ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) {

        return getBean("reactAiCallNode");
    }
}
