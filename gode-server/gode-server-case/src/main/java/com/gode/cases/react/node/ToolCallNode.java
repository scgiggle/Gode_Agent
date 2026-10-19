package com.gode.cases.react.node;

import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.react.AbstractAIAgentReActSupport;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.domain.agent.service.ILongTermMemoryService;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具结果整理节点。
 *
 * <p>工具已经由 ADK Runner 自动执行。本节点不再次调用工具，只负责：</p>
 * <ol>
 *     <li>读取 AiCallNode 写入的工具调用与结果；</li>
 *     <li>按照 toolCallId 匹配调用和结果；</li>
 *     <li>补充工具消息历史；</li>
 *     <li>保存工具消息并提取长期记忆；</li>
 *     <li>路由到 LoopDecisionNode。</li>
 * </ol>
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = dynamicContext.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - 本轮没有工具调用，直接进入结束判断");
            return router(requestParameter, dynamicContext);
        }

        log.info("ReAct ToolCallNode - 开始整理工具结果 toolCalls={}, toolResults={}",
                toolCalls.size(), toolResults == null ? 0 : toolResults.size());

        if (toolResults == null || toolResults.isEmpty()) {
            // 当前架构由 ADK Runner 自动执行工具。没有结果时不在此处手动重试，
            // 避免同一工具被重复调用或产生额外的写操作。
            log.warn("检测到工具调用，但没有找到 ADK 工具结果");
            return router(requestParameter, dynamicContext);
        }

        handleAdkToolResults(dynamicContext, toolCalls, toolResults);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 按 toolCallId 匹配工具调用与结果，并完成消息记录和持久化。
     */
    private void handleAdkToolResults(
            DefaultReActFactory.DynamicContext dynamicContext,
            List<Map<String, Object>> toolCalls,
            List<Map<String, Object>> toolResults) {

        Map<String, Map<String, Object>> resultsById = new HashMap<>();
        for (Map<String, Object> toolResult : toolResults) {
            String toolCallId = valueAsString(toolResult.get("id"));
            if (!toolCallId.isBlank()) {
                resultsById.put(toolCallId, toolResult);
            }
        }

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = valueAsString(toolCall.get("id"));
            String toolName = valueAsString(toolCall.get("name"));

            if (toolCallId.isBlank()) {
                log.warn("跳过缺少 id 的工具调用 name={}", toolName);
                continue;
            }

            Map<String, Object> matchedResult = resultsById.get(toolCallId);
            if (matchedResult == null) {
                log.warn("没有找到对应的工具结果 id={}, name={}", toolCallId, toolName);
                continue;
            }

            String resultContent = valueAsString(matchedResult.get("content"));
            String status = valueAsString(matchedResult.get("status"));
            boolean success = !"error".equalsIgnoreCase(status)
                    && !"failed".equalsIgnoreCase(status)
                    && !isFailureContent(resultContent);

            // AiCallNode 负责提取工具事件；这里补齐给后续上下文使用的 tool 消息。
            dynamicContext.appendToolMessage(toolCallId, resultContent);

            longTermMemoryService.saveToolMessage(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    toolName,
                    toolCallId,
                    resultContent,
                    success
            );

            log.info("工具结果整理完成 id={}, name={}, success={}, resultLength={}",
                    toolCallId,
                    toolName,
                    success,
                    resultContent.length());
        }
    }

    /**
     * 判断返回内容中是否含有明显的失败信号。
     */
    private boolean isFailureContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String normalized = content.toLowerCase();
        return normalized.contains("error")
                || normalized.contains("failed")
                || normalized.contains("not found")
                || normalized.contains("no such")
                || normalized.contains("permission denied")
                || normalized.contains("connection refused")
                || normalized.contains("未找到")
                || normalized.contains("不存在")
                || normalized.contains("失败");
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String stringValue) {
            return stringValue;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    @Override
    public StrategyHandler<
            ChatRequestDTO,
            DefaultReActFactory.DynamicContext,
            ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) {

        log.info("ReAct ToolCallNode - 工具结果整理结束，进入 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }
}
