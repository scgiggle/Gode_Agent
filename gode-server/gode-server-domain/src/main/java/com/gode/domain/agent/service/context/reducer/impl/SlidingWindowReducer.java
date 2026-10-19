package com.gode.domain.agent.service.context.reducer.impl;

import com.gode.domain.agent.service.context.reducer.MessageReducer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class SlidingWindowReducer implements MessageReducer {

    private static final int DEFAULT_WINDOW_GROUP_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> window = new ArrayList<>();
        int usedTokens = 0;

        // 从最新消息组向前回收，优先保留最近轮次的完整上下文。
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroup group = groups.get(i);
            int groupTokens = estimateTokens(group);
            if (window.size() >= DEFAULT_WINDOW_GROUP_SIZE || usedTokens + groupTokens > tokenBudget) {
                break;
            }
            window.add(0, group);
            usedTokens += groupTokens;
        }

        return window.stream()
                .sorted(Comparator.comparingInt(MessageGroup::getStartIndex))
                .flatMap(group -> group.getMessages().stream())
                .collect(Collectors.toList());
    }

    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                // assistant 这一条可能一次声明多个 tool_call_id，
                // 所以后续需要把所有属于这次调用批次的 tool result 一起并入同一组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后面的消息仍然是这批 tool_call_id 的结果，就继续吸收到当前组里。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束，后面应开启新分组。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(message), index));
            index++;
        }

        return groups;
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
        Set<String> toolCallIds = new HashSet<>();
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
        String role = stringValue(message.get("role"));
        String type = stringValue(message.get("type"));

        if (!"tool".equals(role) && !"tool_result".equals(type)) {
            return false;
        }

        String toolCallId = stringValue(message.get("tool_call_id"));
        if (toolCallId.isEmpty()) {
            toolCallId = stringValue(message.get("tool_use_id"));
        }

        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int estimateToken(Map<String, Object> message) {
        String content = stringValue(message.get("content"));
        return content.length() / 2;
    }

    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 该组在原始消息列表中的起始索引。 */
        private int startIndex;
    }

}
