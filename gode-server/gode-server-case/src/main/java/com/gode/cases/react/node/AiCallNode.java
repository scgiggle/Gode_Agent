package com.gode.cases.react.node;

import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.react.AbstractAIAgentReActSupport;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.model.valobj.intent.IntentResultVO;
import com.gode.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.gode.domain.agent.model.valobj.intent.TaskStateVO;
import com.gode.domain.agent.service.IChatContextService;
import com.gode.domain.agent.service.IIntentService;
import com.gode.domain.agent.service.ILongTermMemoryService;
import com.gode.domain.agent.service.IPromptService;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.intent.IntentService;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ReAct 流程中的 AI 调用节点。
 *
 * <p>负责意图识别、上下文裁剪、动态 Prompt 注入、调用 ADK Runner、
 * 处理流式事件、记录工具结果并将执行状态写回 DynamicContext。</p>
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IIntentService intentService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /**
     * ADK stateDelta 的键与业务工具/子 Agent 名称之间的映射。
     * 未配置的键由 {@link #resolveToolName(String)} 自动转换。
     */
    private static final Map<String, String> STATE_DELTA_TOOL_MAPPING = Map.of(
            "problem_analysis", "problemAnalyzer",
            "solution_guidance", "solutionCoach"
    );

    @Override
    protected ReActResultDTO doApply(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        log.info("ReAct AiCallNode - 开始第 {} 步 AI 调用", dynamicContext.getStep() + 1);

        // 1. 获取当前 Agent 对应的 Runner。
        AiAgentRegisterVO agentRegister =
                defaultArmoryFactory.getAiAgentRegisterVO(dynamicContext.getAgentId());
        if (agentRegister == null) {
            throw new IllegalStateException("Agent not found: " + dynamicContext.getAgentId());
        }

        Runner runner = agentRegister.getRunner();
        if (runner == null) {
            throw new IllegalStateException("Runner not found: " + dynamicContext.getAgentId());
        }

        // 2. 获取本轮用户消息。
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // 3. 使用当前 Agent 的模型配置识别用户意图。
        if (agentRegister.getOpenAiApi() != null) {
            intentService.configure(
                    agentRegister.getOpenAiApi(),
                    agentRegister.getChatModelName()
            );
        }

        IntentResultVO intentResult = intentService.classify(
                dynamicContext.getSessionId(),
                dynamicContext.getUserId(),
                lastUserMessage
        );

        if (intentResult != null && intentResult.getIntent() != null) {
            log.info(
                    "识别到用户意图 intent={}, confidence={}, candidates={}, reclassified={}",
                    intentResult.getIntent(),
                    intentResult.getConfidence(),
                    intentResult.getCandidateIntents(),
                    intentResult.isReclassified()
            );

            dynamicContext.setCurrentIntent(intentResult.getIntent().name());
            dynamicContext.setCurrentIntentResult(intentResult);
            syncTaskStateAfterClassification(dynamicContext, lastUserMessage, intentResult);

            if (intentResult.getIntent() == IntentTypeEnumVO.COMPOUND) {
                log.info("检测到复合意图，交给主模型拆解 candidates={}",
                        intentResult.getCandidateIntents());
            } else if (intentResult.getIntent() == IntentTypeEnumVO.UNKNOWN
                    || intentResult.getConfidence() < 0.5) {
                log.info("意图不确定，交给主模型决策 intent={}, confidence={}",
                        intentResult.getIntent(), intentResult.getConfidence());
            }
        }

        // 4. 清空上一轮的临时缓冲区。
        dynamicContext.resetRoundBuffers();
        dynamicContext.resetRoundToolCalls();

        // 5. 按 token 预算裁剪历史消息。
        List<Map<String, Object>> trimmedHistory =
                chatContextService.trimHistory(dynamicContext.getMessageHistory(), 8000);
        dynamicContext.setMessageHistory(new ArrayList<>(trimmedHistory));

        // 6. 收集动态上下文，并追加到本轮用户消息末尾。
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("动态上下文注入完成，消息长度 {} -> {}",
                lastUserMessage.length(), enrichedMessage.length());

        // 7. 保存用户消息并提取长期记忆。对应方法后续在长期记忆服务中补充。
        longTermMemoryService.saveUserMessage(
                dynamicContext.getUserId(),
                dynamicContext.getSessionId(),
                lastUserMessage,
                dynamicContext.getCurrentIntent(),
                dynamicContext.getStep() == 0
        );

        Content userContent = Content.builder()
                .role("user")
                .parts(Part.builder().text(enrichedMessage).build())
                .build();

        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        boolean hasError = false;

        try {
            RunConfig runConfig = RunConfig.builder()
                    .streamingMode(RunConfig.StreamingMode.SSE)
                    .build();

            Iterator<Event> events = runner.runAsync(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    userContent,
                    runConfig
            ).blockingIterable().iterator();

            int eventCount = 0;
            while (events.hasNext()) {
                Event event = events.next();
                eventCount++;

                String eventText = event.stringifyContent();
                if (eventText == null) {
                    eventText = "";
                }

                log.debug("处理第 {} 个 ADK 事件 final={}, contentLength={}",
                        eventCount, event.finalResponse(), eventText.length());

                // 8.1 将模型文本片段实时发送给前端。
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                    dynamicContext.setAssistantContent(textAccumulator);
                    sendTextEvent(emitter, eventText, textAccumulator.toString());
                }

                // 8.2 从 stateDelta 中提取工具或子 Agent 的执行结果。
                EventActions actions = event.actions();
                if (actions != null) {
                    handleStateDelta(actions, dynamicContext, emitter);
                }
            }

            if (textAccumulator.length() > 0) {
                dynamicContext.appendAssistantMessage(textAccumulator.toString());
            }

            log.info("ADK Runner 事件处理完成 eventCount={}", eventCount);
        } catch (Exception exception) {
            hasError = true;
            String errorMessage = "ADK Runner error: " + exception.getMessage();
            dynamicContext.setErrorMessage(errorMessage);
            dynamicContext.setStopReason("error");
            log.error("ADK Runner 调用失败", exception);
        }

        // 9. 更新步骤统计。
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());

        // 10. 保存助手回复并提取长期记忆。对应方法后续在长期记忆服务中补充。
        if (textAccumulator.length() > 0) {
            longTermMemoryService.saveAssistantMessage(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    textAccumulator.toString()
            );
        }

        sendRoundEndEvent(
                emitter,
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getTotalToolCallCount().get()
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(),
                dynamicContext.getRoundToolCallCount().get(),
                textAccumulator.length());

        return router(requestParameter, dynamicContext);
    }

    /**
     * 读取并处理 ADK 事件中的状态变化。
     */
    private void handleStateDelta(
            EventActions actions,
            DefaultReActFactory.DynamicContext dynamicContext,
            ResponseBodyEmitter emitter) {

        Map<String, Object> stateDelta = actions.stateDelta();
        if (stateDelta == null || stateDelta.isEmpty()) {
            return;
        }

        log.info("检测到 stateDelta keys={}", stateDelta.keySet());

        for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
            String stateKey = entry.getKey();
            Object stateValue = entry.getValue();

            if ("REMOVED".equals(stateValue)) {
                continue;
            }

            String toolName = resolveToolName(stateKey);
            String resultContent = formatStateValue(stateValue);
            String toolCallId = "call_" + stateKey + "_" + System.nanoTime();

            Map<String, Object> toolCall = new HashMap<>();
            toolCall.put("id", toolCallId);
            toolCall.put("name", toolName);
            toolCall.put("args", "");
            dynamicContext.getCurrentToolCalls().add(toolCall);
            dynamicContext.getExecutedToolCalls().add(toolCall);

            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("id", toolCallId);
            toolResult.put("name", toolName);
            toolResult.put("content", resultContent);
            toolResult.put("status", "success");
            dynamicContext.getCurrentToolResults().add(toolResult);

            sendToolCallEvent(emitter, toolCallId, toolName, "executing");
            sendToolResultEvent(emitter, toolCallId, resultContent, "success");

            dynamicContext.incrementTotalToolCalls();
            dynamicContext.incrementRoundToolCalls();

            promptService.detectAndRecordMilestone(
                    dynamicContext.getSessionId(),
                    "tool",
                    resultContent
            );
            chatContextService.pushToolResult(
                    dynamicContext.getSessionId(),
                    toolName,
                    resultContent
            );

            handleIntentFeedback(dynamicContext, resultContent);

            log.info("记录工具结果 stateKey={}, toolName={}, resultLength={}",
                    stateKey, toolName, resultContent.length());
        }
    }

    @Override
    public StrategyHandler<
            ChatRequestDTO,
            DefaultReActFactory.DynamicContext,
            ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            log.info("本轮检测到工具调用，进入 ToolCallNode");
            return getBean("reactToolCallNode");
        }

        log.info("本轮没有检测到工具调用，进入 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    private String getLastUserMessage(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) {

        if (dynamicContext.getStep() == 0) {
            return requestParameter.getMessage();
        }

        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int index = history.size() - 1; index >= 0; index--) {
            Map<String, Object> message = history.get(index);
            if ("user".equals(message.get("role"))) {
                return String.valueOf(message.getOrDefault("content", ""));
            }
        }

        return requestParameter.getMessage();
    }

    private String resolveToolName(String stateKey) {
        String mappedName = STATE_DELTA_TOOL_MAPPING.get(stateKey);
        if (mappedName != null) {
            return mappedName;
        }
        if (stateKey != null && stateKey.endsWith("_result")) {
            return stateKey.substring(0, stateKey.length() - "_result".length());
        }
        return stateKey;
    }

    private String formatStateValue(Object stateValue) {
        if (stateValue == null) {
            return "";
        }
        if (stateValue instanceof String stringValue) {
            return stringValue;
        }
        try {
            return objectMapper.writeValueAsString(stateValue);
        } catch (Exception exception) {
            return String.valueOf(stateValue);
        }
    }

    private void handleIntentFeedback(
            DefaultReActFactory.DynamicContext dynamicContext,
            String toolResult) {

        IntentResultVO lastIntent = dynamicContext.getCurrentIntentResult();
        if (lastIntent == null) {
            return;
        }

        boolean success = toolResult != null
                && !toolResult.isBlank()
                && !IntentService.looksLikeIntentMismatch(toolResult);

        IntentResultVO reclassified = intentService.reportFeedback(
                dynamicContext.getSessionId(),
                lastIntent,
                success,
                toolResult
        );

        if (reclassified != null) {
            log.info("工具反馈触发意图重分类 {} -> {} confidence={}",
                    lastIntent.getIntent(),
                    reclassified.getIntent(),
                    reclassified.getConfidence());
            dynamicContext.setCurrentIntent(reclassified.getIntent().name());
            dynamicContext.setCurrentIntentResult(reclassified);
        }

        updateTaskStateAfterFeedback(dynamicContext, success, reclassified);
    }

    private void syncTaskStateAfterClassification(
            DefaultReActFactory.DynamicContext dynamicContext,
            String lastUserMessage,
            IntentResultVO intentResult) {

        if (intentResult == null || intentResult.getIntent() == null) {
            return;
        }

        String sessionId = dynamicContext.getSessionId();
        IntentTypeEnumVO intent = intentResult.getIntent();
        TaskStateVO taskState = intentService.getTaskState(sessionId);

        if (intent == IntentTypeEnumVO.CONTINUE) {
            if (taskState != null && !taskState.isCompleted()) {
                if (taskState.getCurrentStepIndex() < 0) {
                    taskState.setCurrentStepIndex(0);
                }
                taskState.setLastFeedbackFailed(false);
                intentService.updateTaskState(sessionId, taskState);
                if (taskState.getRootIntent() != null) {
                    dynamicContext.setCurrentIntent(taskState.getRootIntent().name());
                }
            }
            return;
        }

        if (intent == IntentTypeEnumVO.UNKNOWN || intent == IntentTypeEnumVO.CHAT) {
            return;
        }

        boolean shouldReplace = taskState == null
                || taskState.isCompleted()
                || taskState.getRootIntent() != intent;

        if (shouldReplace) {
            taskState = TaskStateVO.builder()
                    .taskDescription(lastUserMessage)
                    .rootIntent(intent)
                    .steps(new ArrayList<>(List.of(lastUserMessage)))
                    .currentStepIndex(0)
                    .completed(false)
                    .lastFeedbackFailed(false)
                    .build();
        } else {
            if (taskState.getTaskDescription() == null
                    || taskState.getTaskDescription().isBlank()) {
                taskState.setTaskDescription(lastUserMessage);
            }
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(lastUserMessage)));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            taskState.setCompleted(false);
            taskState.setLastFeedbackFailed(false);
        }

        intentService.updateTaskState(sessionId, taskState);
    }

    private void updateTaskStateAfterFeedback(
            DefaultReActFactory.DynamicContext dynamicContext,
            boolean success,
            IntentResultVO reclassified) {

        TaskStateVO taskState = intentService.getTaskState(dynamicContext.getSessionId());
        if (taskState == null) {
            return;
        }

        if (reclassified != null
                && reclassified.getIntent() != null
                && reclassified.getIntent() != IntentTypeEnumVO.UNKNOWN
                && reclassified.getIntent() != IntentTypeEnumVO.CONTINUE
                && reclassified.getIntent() != IntentTypeEnumVO.CHAT) {
            taskState.setRootIntent(reclassified.getIntent());
        }

        taskState.setLastFeedbackFailed(!success);

        if (success) {
            if (taskState.getSteps() == null || taskState.getSteps().isEmpty()) {
                taskState.setSteps(new ArrayList<>(List.of(taskState.getTaskDescription())));
            }
            if (taskState.getCurrentStepIndex() < 0) {
                taskState.setCurrentStepIndex(0);
            }
            if (taskState.getCurrentStepIndex() >= taskState.getSteps().size() - 1) {
                taskState.setCompleted(true);
            } else {
                taskState.setCurrentStepIndex(taskState.getCurrentStepIndex() + 1);
            }
        }

        intentService.updateTaskState(dynamicContext.getSessionId(), taskState);
    }

    private String buildEnrichedMessage(
            String userMessage,
            DefaultReActFactory.DynamicContext dynamicContext) {

        promptService.detectAndRecordMilestone(
                dynamicContext.getSessionId(),
                "user",
                userMessage
        );

        return promptService.buildEnrichedMessage(
                userMessage,
                dynamicContext.getSessionId(),
                dynamicContext.getUserId(),
                dynamicContext.getRecentCommands(),
                dynamicContext.getMessageHistory(),
                dynamicContext.getCurrentIntent()
        );
    }
}
