package com.gode.domain.agent.service.context.reducer.impl;

import com.gode.domain.agent.service.context.reducer.MessageReducer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PriorityReducer implements MessageReducer {

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        // 空输入直接返回空列表，避免后续 subList / 排序逻辑做无意义处理。
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 先将消息归并成“可裁剪单元”，普通消息单独成组，工具调用链按组处理。
        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        // 至少保留最近 2 个消息组，优先保障当前轮对话和最近一次工具调用链路完整。
        int minKeep = Math.min(2, groups.size());
        int startIndex = groups.size() - minKeep;

        // kept 先放入保底消息组，后续只在预算允许时继续补充更早的历史组。
        List<MessageGroup> kept = new ArrayList<>(groups.subList(startIndex, groups.size()));
        int usedTokens = estimateTokens(kept);

        // 候选消息组只来自更早历史，避免和“最近必保留组”重复。
        List<MessageGroup> candidates = new ArrayList<>(groups.subList(0, startIndex));
        candidates.sort(Comparator
                // 高优先级优先保留，例如错误结果、关键 system/user 指令、tool_calls。
                .comparing((MessageGroup g) -> g.getPriority().weight()).reversed()
                // 同优先级时，越新的历史组越优先保留，尽量让上下文更贴近当前问题。
                .thenComparing(MessageGroup::getStartIndex, Comparator.reverseOrder()));

        // 按排序后的优先级依次尝试回填，只要还没超预算就整组加入。
        for (MessageGroup group : candidates) {
            int groupTokens = estimateTokens(group);
            if (usedTokens + groupTokens <= tokenBudget) {
                kept.add(group);
                usedTokens += groupTokens;
            }
        }

        // 虽然回填阶段按优先级选组，但最终输出必须恢复原始时间顺序。
        kept.sort(Comparator.comparing(MessageGroup::getStartIndex));
        return kept.stream()
                .flatMap(group -> group.getMessages().stream())
                .collect(Collectors.toList());
    }

    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                // assistant 发起工具调用时，先把当前 assistant 消息加入组头。
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                // 收集本次 assistant 工具调用中声明的全部 tool_call_id。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后续消息还是当前 tool_call_id 对应的 tool result，就继续并入同一组。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, inferPriority(groupedMessages), index));
                index = next;
                continue;
            }

            // 补充：兼容当前 ADK 自动执行分支缺失 assistant tool_calls 的情况，把孤立的 tool result 也单独成组
            // 避免 tool result 和下一个 assistant / user 合并导致越界
            groups.add(new MessageGroup(List.of(message), inferPriority(List.of(message)), index));
            index++;
        }

        return groups;
    }

    private Priority inferPriority(List<Map<String, Object>> messages) {
        return messages.stream()
                .map(this::inferPriority)
                .max(Comparator.comparingInt(Priority::weight))
                .orElse(Priority.MEDIUM);
    }

    private Priority inferPriority(Map<String, Object> message) {
        String role = stringValue(message.get("role"));
        String content = stringValue(message.get("content"));

        // 工具报错信息最关键，必须尽量保留给模型用于纠错。
        if (("tool".equals(role) || "tool_result".equals(stringValue(message.get("type"))))
                && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
        }
        // assistant 里出现 tool_calls，说明这是调用工具的入口消息，和 tool result 配对价值很高。
        if ("assistant".equals(role) && hasToolCalls(message)) {
            return Priority.HIGH;
        }
        // 用户显式给出路径或配置文件时，通常是关键操作目标，不应轻易裁掉。
        if ("user".equals(role) && containsAny(content, "/", ".conf", ".yml", ".properties")) {
            return Priority.HIGH;
        }
        // system 指令默认高优先级，避免基础行为约束被裁掉。
        if ("system".equals(role)) {
            return Priority.HIGH;
        }
        // 超长 assistant 文本通常是冗长说明，优先级可降低。
        if ("assistant".equals(role) && content.length() > 5000) {
            return Priority.LOW;
        }
        return Priority.MEDIUM;
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

    private boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
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

    private boolean containsAny(String content, String... keywords) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }


    private int estimateToken(Map<String, Object> message) {
        String content = stringValue(message.get("content"));
        return content.length() / 2;
    }

    private int estimateTokens(List<MessageGroup> groups) {
        return groups.stream().mapToInt(this::estimateTokens).sum();
    }

    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    private enum Priority {
        CRITICAL(100),
        HIGH(80),
        MEDIUM(50),
        LOW(20);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 组优先级，通常取组内消息最高优先级。 */
        private Priority priority;
        /** 该组在原始消息列表中的起始索引，用于最终恢复时间顺序。 */
        private int startIndex;
    }
}
