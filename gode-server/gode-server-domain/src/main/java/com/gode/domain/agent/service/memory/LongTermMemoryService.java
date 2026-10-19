package com.gode.domain.agent.service.memory;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.model.entity.LongTermMemoryEntity;
import com.gode.domain.agent.service.ILongTermMemoryService;
import jakarta.annotation.Resource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** 学习偏好只采用用户明确的长期表达，不把本轮“请用 Java”自动升级为全局偏好。 */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";
    /** 用户自己明确表达的知识薄弱点，不由助手猜测。 */
    private static final String TYPE_KNOWLEDGE_GAP = "KNOWLEDGE_GAP";
    /** 用户自己明确表达的反复犯错习惯，不从一次失败推断“总是不会”。 */
    private static final String TYPE_COMMON_MISTAKE = "COMMON_MISTAKE";
    /** 助手的学习要点，属于参考总结，不属于用户能力事实。 */
    private static final String TYPE_ALGORITHM_INSIGHT = "ALGORITHM_INSIGHT";
    /** 成功读取的真实提交状态；不等于已确定错误原因。 */
    private static final String TYPE_SUBMISSION_CASE = "SUBMISSION_CASE";
    private static final Set<String> LEARNING_TYPES = Set.of(TYPE_USER_PREFERENCE, TYPE_KNOWLEDGE_GAP,
            TYPE_COMMON_MISTAKE, TYPE_ALGORITHM_INSIGHT, TYPE_SUBMISSION_CASE);
    private static final Pattern DURABLE_PREFERENCE = Pattern.compile("以后|默认|我习惯|我更喜欢|我偏好|统一使用|记住");
    private static final Pattern KNOWLEDGE_GAP = Pattern.compile("我.{0,12}(?:不懂|不理解|不熟悉|不会|薄弱|不擅长|还没掌握)");
    private static final Pattern COMMON_MISTAKE = Pattern.compile("我.{0,12}(?:总是|经常|容易|反复).{0,20}(?:忘|错|漏|越界|混淆)");
    private static final List<String> LEARNING_STYLE = List.of("中文", "英文", "提示", "引导", "完整代码", "完整答案", "解题", "算法");
    /** 固定领域词与别名，补足中文整句无法按空格切词的问题。 */
    private static final Map<String, List<String>> TOPIC_ALIASES = Map.ofEntries(
            Map.entry("哈希表", List.of("哈希表", "hashmap", "hash map", "hash table")),
            Map.entry("双指针", List.of("双指针", "two pointers")),
            Map.entry("滑动窗口", List.of("滑动窗口", "sliding window")),
            Map.entry("二分", List.of("二分", "binary search")),
            Map.entry("动态规划", List.of("动态规划", "dynamic programming", "dp")),
            Map.entry("回溯", List.of("回溯", "backtracking")),
            Map.entry("递归", List.of("递归", "recursion")),
            Map.entry("链表", List.of("链表", "linked list")),
            Map.entry("二叉树", List.of("二叉树", "binary tree")),
            Map.entry("图论", List.of("图论", "graph")),
            Map.entry("边界", List.of("边界", "越界", "下标", "自匹配", "off-by-one", "重复元素")),
            Map.entry("复杂度", List.of("复杂度", "complexity")),
            Map.entry("java", List.of("java")), Map.entry("python", List.of("python", "python3")),
            Map.entry("c++", List.of("c++")), Map.entry("javascript", List.of("javascript", "typescript")),
            Map.entry("go", List.of("go", "golang"))
    );
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    /** 会话历史仓储（消息落库 + 冷启动恢复） */
    @Resource
    private IChatHistoryRepository chatHistoryRepository;
    /**
     * 提取明确的长期学习偏好、自述薄弱点与常见错误；忽略代码块、动态上下文和过长片段。
     * 本轮“不想看答案”仍是本轮约束，只有“以后/默认”等表达才记录为长期偏好。
     */
    @Override
    public void recordUserMessage(String userId, String sessionId, String message, String intentLabel) {
        if (isBlank(userId) || isBlank(message)) return;
        for (String statement : learningStatements(message)) {
            String normalized = normalize(statement);
            boolean learningRelated = hasLearningTopic(normalized)
                    || LEARNING_STYLE.stream().anyMatch(normalized::contains);
            if (DURABLE_PREFERENCE.matcher(normalized).find() && learningRelated) {
                recordLearningMemory(userId, sessionId, TYPE_USER_PREFERENCE, "pref:", statement,
                        "user", 0.85, intentLabel);
            }
            // 问句、假设或否定自述不是可确认的用户薄弱点。
            if (!hasLearningTopic(normalized)
                    || Pattern.compile("吗|是不是|是否|假设|如果|(?:不是|并非).{0,8}(?:不懂|不熟悉|不会|总是|经常)|(?:不|并不)(?:总是|经常|容易)")
                    .matcher(normalized).find()) continue;
            if (COMMON_MISTAKE.matcher(normalized).find()) {
                recordLearningMemory(userId, sessionId, TYPE_COMMON_MISTAKE, "mistake:", statement,
                        "user", 0.85, intentLabel);
            } else if (KNOWLEDGE_GAP.matcher(normalized).find()) {
                recordLearningMemory(userId, sessionId, TYPE_KNOWLEDGE_GAP, "gap:", statement,
                        "user", 0.85, intentLabel);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 冷启动恢复：委托仓储层取最近 N 条消息（时间正序）。
     */
    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        if (isBlank(sessionId)) {
            return List.of();
        }
        try {
            return chatHistoryRepository.getRecentMessages(sessionId, limit);
        } catch (Exception e) {
            log.warn("查询最近消息失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }


    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }
        // 阶段一：粗筛——从 DB 拉最近 120 条记忆（也可以提取出来做一个可配置的）
        List<LongTermMemoryEntity> candidates;
        try {
            candidates = longTermMemoryRepository.queryRecentByUserId(userId, 120);
        } catch (Exception e) {
            log.warn("查询长期学习记忆失败 userId={}", userId, e);
            return List.of();
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }


        // 阶段二：精排——关键词重叠打分
        Set<String> queryKeywords = extractKeywords(query);
        return candidates.stream()
                // 保留历史数据，但不再将旧运维类型或与学习无关的偏好注入算法对话。
                .filter(Objects::nonNull)
                .filter(memory -> LEARNING_TYPES.contains(nullSafe(memory.getMemoryType())))
                .filter(memory -> !TYPE_USER_PREFERENCE.equals(memory.getMemoryType())
                        || hasLearningTopic(normalize(memory.getContent()))
                        || LEARNING_STYLE.stream().anyMatch(normalize(memory.getContent())::contains))
                .map(memory -> new MemoryScore(memory, score(memory, queryKeywords)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt(MemoryScore::score).reversed()
                        .thenComparing(ms -> ms.memory.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit > 0 ? limit : 5)
                .map(MemoryScore::memory)
                .collect(Collectors.toList());

    }

    /**
     * {@inheritDoc}
     * <p>
     * 输出格式：每行一条记忆，"- [类型] 内容"
     */
    @Override
    public String buildMemorySummary(String userId, String query, int limit) {
        List<LongTermMemoryEntity> memories = queryRelevantMemories(userId, query, limit);
        if (memories.isEmpty()) {
            return "";
        }

        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + truncate(memory.getContent(), 160))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 保存长期记忆（try-catch 旁路写入，失败不影响主流程）
     */
    private void saveMemory(LongTermMemoryEntity memoryEntity) {
        try {
            longTermMemoryRepository.saveOrUpdate(memoryEntity);
        } catch (Exception e) {
            log.warn("保存长期记忆失败 type={}, key={}", memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(), e);
        }
    }

    /**
     * 关键词提取：分割 → 过滤 → 去重。
     * <p>
     * <ol>
     *   <li>分割：用 [^a-z0-9_\u4e00-\u9fa5]+ 正则分割，保留英文/数字/下划线/中文</li>
     *   <li>过滤：长度 < 2 的片段丢弃，停用词丢弃</li>
     *   <li>去重：LinkedHashSet 保证唯一且保持插入顺序</li>
     * </ol>
     *
     * @param text 原始文本
     * @return 关键词集合
     */
    private Set<String> extractKeywords(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        String normalized = normalize(text);
        String[] parts = normalized.split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        Set<String> keywords = new LinkedHashSet<>();
        Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(part -> !STOP_WORDS.contains(part))
                .forEach(keywords::add);
        TOPIC_ALIASES.forEach((topic, aliases) -> {
            if (aliases.stream().anyMatch(alias -> containsTopic(normalized, alias))) keywords.add(topic);
        });
        return keywords;
    }


    /**
     * 计算记忆与查询关键词的匹配分数。
     * <p>
     * 打分公式：关键词重叠数 × 2 + USER_PREFERENCE 加权（+1）。
     * USER_PREFERENCE 有额外加分，因为用户偏好是全局适用的——无论用户问什么，
     * "默认用 Java 并先给提示"这条偏好都应该被召回。
     *
     * @param memory         长期记忆
     * @param queryKeywords  查询关键词集合
     * @return 匹配分数，0 表示不匹配
     */
    private int score(LongTermMemoryEntity memory, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) {
            // 无查询关键词时，仅 USER_PREFERENCE 返回 1 分（全局适用）
            return TYPE_USER_PREFERENCE.equals(memory.getMemoryType()) ? 1 : 0;
        }

        Set<String> memoryKeywords = extractKeywords(nullSafe(memory.getKeywords()) + " " + nullSafe(memory.getContent()));
        int overlap = 0;
        for (String keyword : queryKeywords) {
            if (memoryKeywords.contains(keyword)) {
                overlap += 2;
            }
        }
        // 用户偏好记忆额外加 1 分（全局适用，应优先召回）
        if (TYPE_USER_PREFERENCE.equals(memory.getMemoryType())) {
            overlap += 1;
        }
        return overlap;
    }

    private String joinKeywords(Set<String> keywords) {
        return String.join(",", keywords);
    }

    /**
     * 文本归一化：转小写 + 换行替换为空格 + trim
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
    }

    /**
     * 截断文本，超长部分用 "..." 表示
     */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * SHA-1 哈希，用于生成 memory_key 去重键。
     * 失败时降级为 hashCode，保证不抛异常。
     */
    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** 记忆打分内部记录类，用于排序 */
    private record MemoryScore(LongTermMemoryEntity memory, int score) {}

    /** 中英文停用词集合：在关键词提取时被过滤，避免无意义词干扰匹配 */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(List.of(
            "请", "帮", "一下", "这个", "那个", "现在", "需要", "进行", "继续", "问题", "情况",
            "the", "and", "for", "with", "that", "this", "from", "into", "have", "has"
    ));

    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent) {
        if (isBlank(assistantContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(assistantContent)
                    .priority("MEDIUM")
                    .tokenCount(assistantContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存助手消息落库失败 sessionId={}", sessionId, e);
        }
        recordAssistantConclusion(userId, sessionId, assistantContent);
    }

    @Override
    public void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound) {
        if (firstRound && !isBlank(sessionId) && !isBlank(message)) {
            try {
                chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(message)
                        .priority("MEDIUM")
                        .tokenCount(message.length() / 2)
                        .build());
            } catch (Exception e) {
                log.warn("保存用户消息落库失败 sessionId={}", sessionId, e);
            }
        }
        recordUserMessage(userId, sessionId, message, intentLabel);
    }

    /** 保存明确的算法学习要点，标明助手总结，不推断用户已经掌握或存在某种弱点。 */
    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent) {
        if (isBlank(userId) || isBlank(assistantContent)) return;
        for (String statement : learningStatements(assistantContent)) {
            String normalized = normalize(statement);
            if (hasLearningTopic(normalized)
                    && Pattern.compile("要点|关键|结论|记住|应当|应该|避免|注意").matcher(normalized).find()) {
                recordLearningMemory(userId, sessionId, TYPE_ALGORITHM_INSIGHT, "insight:",
                        "学习参考（助手总结，非用户能力评估）：" + statement, "assistant", 0.65, null);
                return; // 每次回答最多一条，避免把整篇解答当成用户画像。
            }
        }
    }

    @Override
    public void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success) {
        if (isBlank(resultContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .priority("HIGH")
                    .tokenCount(resultContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存工具消息落库失败 sessionId={}, tool={}", sessionId, toolName, e);
        }
        recordToolObservation(userId, sessionId, toolName, resultContent, success);
    }

    /**
     * 仅从提交/判题工具的结构化响应记录提交状态。
     * success 表示工具读取成功，不代表题目 Accepted；网络、文件、MCP 失败不记为学习弱点。
     */
    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success) {
        if (!success || isBlank(userId) || isBlank(resultContent) || isBlank(toolName)) return;
        String name = normalize(toolName);
        if (!(name.contains("submission") || name.contains("judge"))) return;
        try {
            JsonNode submission = findSubmission(objectMapper.readTree(resultContent), 0);
            if (submission == null) return;
            String slug = textField(submission, "titleSlug", "title_slug", "problemSlug", "problem_slug", "questionSlug");
            if (isBlank(slug) && submission.path("question").isObject()) {
                slug = textField(submission.path("question"), "titleSlug", "title_slug");
            }
            String verdict = submissionVerdict(submission);
            String language = textField(submission, "lang", "language");
            String summary = "题目 " + slug + " 的历史提交判题状态为 " + verdict
                    + (isBlank(language) ? "" : "，语言 " + language)
                    + "；仅记录该次提交，不据此推断用户长期能力或具体错因。";
            recordLearningMemory(userId, sessionId, TYPE_SUBMISSION_CASE, "submission:", summary,
                    "tool", 0.85, slug + " " + verdict);
        } catch (Exception ignored) {
            // 普通文字、代码、解释或非法 JSON 不构成真实提交证据。
        }
    }

    /** 支持常见 data/result 包装与 MCP 文本内容块，不扫描任意字段中的代码/示例。 */
    private JsonNode findSubmission(JsonNode node, int depth) {
        if (node == null || depth > 5) return null;
        if (node.isObject()) {
            String slug = textField(node, "titleSlug", "title_slug", "problemSlug", "problem_slug", "questionSlug");
            if (isBlank(slug)) slug = textField(node.path("question"), "titleSlug", "title_slug");
            if (!isBlank(slug) && !isBlank(submissionVerdict(node))) return node;
            for (String field : List.of("data", "result", "submission", "submissionDetails", "submissionDetail", "content")) {
                JsonNode found = findSubmission(node.get(field), depth + 1);
                if (found != null) return found;
            }
            if ("text".equals(node.path("type").asText()) && node.path("text").isTextual()) {
                try {
                    return findSubmission(objectMapper.readTree(node.path("text").asText()), depth + 1);
                } catch (Exception ignored) { return null; }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findSubmission(item, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String submissionVerdict(JsonNode node) {
        String raw = textField(node, "statusDisplay", "status_display", "verdict", "status");
        String verdict = raw.toUpperCase(Locale.ROOT).replace(' ', '_');
        return Set.of("ACCEPTED", "WRONG_ANSWER", "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED",
                "RUNTIME_ERROR", "COMPILE_ERROR").contains(verdict) ? verdict : "";
    }

    private String textField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return "";
    }

    private void recordLearningMemory(String userId, String sessionId, String type, String prefix,
                                      String content, String role, double confidence, String extraKeywords) {
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId).sessionId(sessionId).memoryType(type)
                .memoryKey(sha1(prefix + normalize(content))).content(truncate(content, 400))
                .keywords(joinKeywords(extractKeywords(content + " " + nullSafe(extraKeywords))))
                .sourceRole(role).confidence(confidence).hitCount(1).build());
    }

    /** 不从代码块、附加动态上下文或整段题目 JSON 中提取用户画像。 */
    private List<String> learningStatements(String text) {
        String plain = text.replace("\r\n", "\n").replaceAll("(?s)\u0060\u0060\u0060.*?(?:\u0060\u0060\u0060|$)", "")
                .replaceAll("(?s)~~~.*?(?:~~~|$)", "");
        int dynamicStart = plain.indexOf("\n---\n");
        if (dynamicStart >= 0) plain = plain.substring(0, dynamicStart);
        return Arrays.stream(plain.split("[\\r\\n。！？!?]+"))
                .map(String::trim).filter(s -> !s.isEmpty() && s.length() <= 300)
                .filter(s -> !s.startsWith("[") && !s.startsWith("{") && !s.startsWith("#"))
                .filter(s -> !s.contains("class ") && !s.contains("public ") && !s.contains("return "))
                .toList();
    }

    private boolean hasLearningTopic(String normalized) {
        return TOPIC_ALIASES.values().stream().flatMap(List::stream).anyMatch(alias -> containsTopic(normalized, alias));
    }

    private boolean containsTopic(String normalized, String alias) {
        if (alias.charAt(0) > 127) return normalized.contains(alias);
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(alias) + "(?![a-z0-9_])").matcher(normalized).find();
    }
}
