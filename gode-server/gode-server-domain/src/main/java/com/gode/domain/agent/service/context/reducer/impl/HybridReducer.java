package com.gode.domain.agent.service.context.reducer.impl;

import com.gode.domain.agent.service.context.reducer.MessageReducer;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class HybridReducer implements MessageReducer {

    /**
     * 负责按重要性筛选历史消息，并尽量保留关键错误、路径和工具调用链。
     */
    @Resource
    private PriorityReducer priorityReducer;

    /**
     * 负责按时间顺序保留最近的消息组，避免当前对话上下文断层。
     */
    @Resource
    private SlidingWindowReducer slidingReducer;


    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> priorityResult = priorityReducer.reduce(messages, tokenBudget);
        List<Map<String, Object>> slidingResult = slidingReducer.reduce(messages, tokenBudget);

        // 第一步：先收 PriorityReducer 的结果，作为"重要历史主集合"。
        Set<Integer> keepIndices = new LinkedHashSet<>(indexSet(priorityResult, messages));

        // 用最近窗口结果补充优先级裁剪中未保留的消息，强化当前轮上下文连续性。
        keepIndices.addAll(indexSet(slidingResult, messages));

        // 统一保底最近 2 个完整消息组，而不是最近 2 条单消息，避免把工具调用链截断。
        List<MessageGroup> groups = groupMessages(messages);
        int minKeepGroups = Math.min(2, groups.size());
        for (int i = groups.size() - minKeepGroups; i < groups.size(); i++) {
            keepIndices.addAll(groups.get(i).getIndices());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) {
                result.add(messages.get(i));
            }
        }
        return result;
    }

    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new LinkedHashSet<>();
        boolean[] used = new boolean[all.size()];

        for (Map<String, Object> msg : subset) {
            for (int i = 0; i < all.size(); i++) {
                if (!used[i] && all.get(i) == msg) {
                    indices.add(i);
                    used[i] = true;
                    break;
                }
            }
        }

        return indices;
    }

    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                List<Integer> indices = new ArrayList<>();
                indices.add(index);

                // assistant 声明的这批 tool_call_id，决定后面哪些 tool result 应该并入同组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        indices.add(next);
                        next++;
                        continue;
                    }
                    break;
                }

                groups.add(new MessageGroup(indices, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(index), index));
            index++;
        }

        return groups;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }


    private boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
    }

    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new LinkedHashSet<>();
        Object toolCalls = message.get("tool_calls");
        if (!(toolCalls instanceof List<?> list)) {
            return toolCallIds;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> call) {
                Object id = ((Map<String, Object>) call).get("id");
                if (id != null) {
                    toolCallIds.add(String.valueOf(id));
                }
            }
        }
        return toolCallIds;
    }

    private boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        if (!"tool".equals(stringValue(message.get("role")))) {
            return false;
        }
        String toolCallId = stringValue(message.get("tool_call_id"));
        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    /**
     * 消息组。
     *
     * <p>HybridReducer 不直接关心组内消息内容，只关心组覆盖的原始索引范围，
     * 以便在最终合并阶段稳定地保留完整上下文单元。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 该消息组覆盖的原始消息索引列表。 */
        private List<Integer> indices;
        /** 该组在原始消息列表中的起始位置。 */
        private int startIndex;
    }

}
