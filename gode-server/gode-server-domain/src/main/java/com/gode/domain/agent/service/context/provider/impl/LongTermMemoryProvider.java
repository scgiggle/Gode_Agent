package com.gode.domain.agent.service.context.provider.impl;

import com.gode.domain.agent.service.ILongTermMemoryService;
import com.gode.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LongTermMemoryProvider implements ContextProvider {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-memory";
    }

    /**
     * 排序权重 25，位于 TaskProvider(20) 与 MilestoneProvider(30) 之间
     */
    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = buildQuery(messageHistory);
        String summary = longTermMemoryService.buildMemorySummary(userId, query, 5);
        if (!summary.isBlank()) {
            result.put("longTermMemorySummary", summary);
        }
        return result;
    }


    private String buildQuery(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }

        String latestUser = "";
        String firstUser = "";
        for (Map<String, Object> message : messageHistory) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (firstUser.isBlank()) {
                firstUser = content;
            }
            latestUser = content;
        }
        return (firstUser + " " + latestUser).trim();
    }

}
