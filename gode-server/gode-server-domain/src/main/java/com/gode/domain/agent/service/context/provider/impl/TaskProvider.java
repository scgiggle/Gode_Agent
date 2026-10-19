package com.gode.domain.agent.service.context.provider.impl;

import com.gode.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskProvider implements ContextProvider {

    @Resource
    private com.gode.domain.agent.service.IIntentService intentService;

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // 当前任务态只能来自本会话；切题后不再把本会话最早一道题固定当成当前任务。
        var task = intentService.getTaskState(sessionId);
        if (task != null && !task.isCompleted() && task.getTaskDescription() != null) {
            result.put("taskDescription", stripDynamicSuffix(task.getTaskDescription()));
        } else if (messageHistory != null) {
            for (int i = messageHistory.size() - 1; i >= 0; i--) {
                Map<String, Object> message = messageHistory.get(i);
                if ("user".equals(message.get("role")) && message.get("content") instanceof String content) {
                    result.put("taskDescription", stripDynamicSuffix(content));
                    break;
                }
            }
        }

        return result;

    }


    private String stripDynamicSuffix(String text) {
        if (text == null) return null;
        if (text.contains("\n---\n")) {
            String[] parts = text.split("\\n---\\n", 2);
            return parts.length == 2 ? parts[0].trim() : text;
        }
        return text;
    }

}
