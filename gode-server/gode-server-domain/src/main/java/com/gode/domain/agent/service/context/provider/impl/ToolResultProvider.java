package com.gode.domain.agent.service.context.provider.impl;

import com.gode.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class ToolResultProvider implements ContextProvider {

    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    private static final int MAX_ENTRIES_PER_SESSION = 50;

    @Override
    public String getName() {
        return "tool-result";
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) return result;

        // 懒摘要：有缓存直接返回，否则重新生成
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }


    public void pushResult(String sessionId, String toolName, String result) {
        List<ToolResultEntry> entries = results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        entries.add(new ToolResultEntry(toolName, result));

        // 限制最大缓存条目数，防止内存泄露
        while (entries.size() > MAX_ENTRIES_PER_SESSION) {
            entries.remove(0);
        }

        summaryCache.remove(sessionId);  // 失效摘要缓存
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            results.remove(sessionId);
            summaryCache.remove(sessionId);
        }
    }


    private String generateSummary(List<ToolResultEntry> entries) {
        // 少量结果直接拼接，大量结果模板化压缩
        if (entries.size() <= 5) {
            return entries.stream()
                    .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                    .collect(Collectors.joining("\n"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("最近执行了 ").append(entries.size()).append(" 个工具调用:\n");
        // 只取最近 5 条详细 + 总结
        List<ToolResultEntry> recent = entries.subList(entries.size() - 5, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
                    .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }

}
