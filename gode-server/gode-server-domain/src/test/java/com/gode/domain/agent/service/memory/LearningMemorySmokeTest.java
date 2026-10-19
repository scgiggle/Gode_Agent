package com.gode.domain.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.gode.domain.agent.model.entity.ChatMessageEntity;
import com.gode.domain.agent.model.entity.LongTermMemoryEntity;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 模拟仓储验证学习记忆提取、召回、旧数据兼容与旁路失败隔离；不访问数据库。 */
public class LearningMemorySmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        LongTermMemoryService service = new LongTermMemoryService();
        FakeRepository repository = new FakeRepository();
        inject(service, "longTermMemoryRepository", repository);
        List<ChatMessageEntity> messages = new ArrayList<>();
        IChatHistoryRepository history = (IChatHistoryRepository) Proxy.newProxyInstance(
                IChatHistoryRepository.class.getClassLoader(), new Class[]{IChatHistoryRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("saveMessage")) { messages.add((ChatMessageEntity) arguments[0]); return null; }
                    if (method.getName().equals("getRecentMessages")) return messages;
                    throw new UnsupportedOperationException(method.getName());
                });
        inject(service, "chatHistoryRepository", history);
        service.recordUserMessage("u", "s", "以后默认用 Java，先给提示", "HINT");
        check(repository.saved.size() == 1 && type(repository.last(), "USER_PREFERENCE"), "明确长期学习偏好");
        check(repository.last().getKeywords().contains("java") && repository.last().getMemoryKey().length() == 40, "学习关键词与去重键");
        String key = repository.last().getMemoryKey();
        service.recordUserMessage("u", "s2", "以后默认用 Java，先给提示", "HINT");
        check(key.equals(repository.last().getMemoryKey()), "跨会话相同内容键一致");
        int count = repository.saved.size();
        service.recordUserMessage("u", "s", "请用 Python，只给提示，不要完整代码", "HINT");
        service.recordUserMessage("u", "s", "以后执行命令都加 sudo", null);
        service.recordUserMessage("u", "s", "\u0060\u0060\u0060java\n// 记住以后默认用 Java\n\u0060\u0060\u0060", null);
        service.recordUserMessage("u", "s", "分析题目\n---\n以后默认用 Java", null);
        service.recordUserMessage("u", "s", "分析题目\r\n---\r\n以后默认用 Java", null);
        service.recordUserMessage(null, "s", "以后默认用 Java", null);
        check(repository.saved.size() == count, "不记录单轮约束、运维偏好、代码、动态上下文与空用户");
        service.recordUserMessage("u", "s", "我还不熟悉动态规划的状态转移", null);
        check(type(repository.last(), "KNOWLEDGE_GAP") && repository.last().getKeywords().contains("动态规划"), "用户自述知识薄弱点");
        service.recordUserMessage("u", "s", "我总是忘记处理数组边界", null);
        check(type(repository.last(), "COMMON_MISTAKE"), "用户自述反复错误");
        count = repository.saved.size();
        service.recordUserMessage("u", "s", "我不是不懂动态规划，只是想验证答案", null);
        service.recordUserMessage("u", "s", "你觉得我不懂动态规划吗", null);
        service.recordUserMessage("u", "s", "假设我经常忘记边界，会有什么问题", null);
        service.recordUserMessage("u", "s", "我不总是忘记数组边界", null);
        check(repository.saved.size() == count, "否定、问句和假设不生成用户薄弱点");
        service.recordAssistantConclusion("u", "s", "原因：connection refused，请重启服务");
        service.recordAssistantConclusion("u", "s", "这道题用哈希表");
        check(repository.saved.size() == count, "运维建议与普通说明不自动入学习画像");
        service.recordAssistantConclusion("u", "s", "关键是哈希表应先查再存，避免当前元素自匹配");
        check(type(repository.last(), "ALGORITHM_INSIGHT") && repository.last().getContent().contains("非用户能力评估"), "助手仅存参考要点");
        check(repository.last().getConfidence() < 0.85 && "assistant".equals(repository.last().getSourceRole()), "助手参考置信度及来源");

        ObjectMapper mapper = new ObjectMapper();
        String submission = mapper.writeValueAsString(Map.of("titleSlug", "two-sum", "statusDisplay", "Wrong Answer", "lang", "java"));
        count = repository.saved.size();
        service.recordToolObservation("u", "s", "read_text_file", submission, true);
        service.recordToolObservation("u", "s", "get_submission", submission, false);
        service.recordToolObservation("u", "s", "get_submission", "error: connection refused", false);
        service.recordToolObservation("u", "s", "get_submission", "例如 two-sum 可以出现 Wrong Answer", true);
        service.recordToolObservation("u", "s", "get_submission", "{\"verdict\":\"WRONG_ANSWER\"}", true);
        service.recordToolObservation("u", "s", "read_text_file", "Ubuntu nginx version: 1.24.0 redis_version:7.0.11", true);
        check(repository.saved.size() == count, "不记录文件、网络、纯文本举例、无题目提交或运维事实");
        service.recordToolObservation("u", "s", "leetcode_get_submission", submission, true);
        check(type(repository.last(), "SUBMISSION_CASE") && repository.last().getContent().contains("WRONG_ANSWER"), "真实结构化失败判题；读取成功不等于 Accepted");
        check(repository.last().getContent().contains("不据此推断"), "一次失败不是长期能力结论");
        String nested = mapper.writeValueAsString(Map.of("content", List.of(Map.of("type", "text", "text", submission))));
        service.recordToolObservation("u", "s", "get_submission", nested, true);
        check(type(repository.last(), "SUBMISSION_CASE"), "MCP 文本内容块");
        service.recordToolObservation("u", "s", "get_submission",
                "{\"data\":{\"submissionDetail\":{\"question\":{\"titleSlug\":\"two-sum\"},\"statusDisplay\":\"Accepted\"}}}", true);
        check(repository.last().getContent().contains("ACCEPTED"), "GraphQL 包装成功状态");

        repository.rows = List.of(
                memory("SOFTWARE_FACT", "redis 版本 7.0.11"),
                memory("ENVIRONMENT_FACT", "Ubuntu"),
                memory("TROUBLESHOOTING_CASE", "历史失败信号"),
                memory("USER_PREFERENCE", "以后执行命令加 sudo"),
                memory("USER_PREFERENCE", "以后默认用 Java"),
                memory("KNOWLEDGE_GAP", "我不熟悉动态规划状态转移"),
                memory("COMMON_MISTAKE", "我总是忘记数组边界"));
        List<LongTermMemoryEntity> recalled = service.queryRelevantMemories("u", "讲解动态规划题目的思路", 5);
        check(recalled.size() == 2 && type(recalled.get(0), "KNOWLEDGE_GAP"), "中文知识点跨整句召回，学习偏好全局可用");
        check(service.queryRelevantMemories("u", "dynamic programming", 5).get(0).getMemoryType().equals("KNOWLEDGE_GAP"), "中英文别名召回");
        check(service.queryRelevantMemories("u", "", 5).size() == 1, "无查询只返回有效学习偏好");
        check(!service.buildMemorySummary("u", "redis Ubuntu", 5).contains("sudo"), "旧运维偏好不污染算法 Prompt");
        check(repository.rows.size() == 7, "未删除或修改已有数据库数据");
        check(service.queryRelevantMemories("u", "动态规划", 1).size() == 1, "召回数量上限");
        service.saveUserMessage("u", "s", "本轮原始用户消息", null, true);
        check(messages.size() == 1 && messages.get(0).getContent().equals("本轮原始用户消息"), "原始用户历史保持");
        service.saveUserMessage("u", "s", "后续内部循环", null, false);
        check(messages.size() == 1, "保留 firstRound 逻辑");
        service.saveAssistantMessage("u", "s", "助手原始回答");
        service.saveToolMessage("u", "s", "read_text_file", "call1", "原始工具输出", true);
        check(messages.size() == 3 && messages.get(2).getContent().equals("原始工具输出"), "不改原始助手和工具历史");
        check(service.getRecentMessages("s", 50).size() == 3, "历史查询保持");
        repository.fail = true;
        service.recordUserMessage("u", "s", "以后默认用 Python", null);
        check(service.queryRelevantMemories("u", "动态规划", 5).isEmpty(), "数据库失败旁路降级");
        System.out.println("LearningMemorySmokeTest: " + checks + " checks passed");
    }
    private static LongTermMemoryEntity memory(String type, String content) {
        return LongTermMemoryEntity.builder().memoryType(type).content(content).build();
    }
    private static boolean type(LongTermMemoryEntity item, String type) { return type.equals(item.getMemoryType()); }
    private static void inject(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static class FakeRepository implements ILongTermMemoryRepository {
        List<LongTermMemoryEntity> saved = new ArrayList<>();
        List<LongTermMemoryEntity> rows = List.of();
        boolean fail;
        public void saveOrUpdate(LongTermMemoryEntity memory) {
            if (fail) throw new IllegalStateException("simulated DB write failure");
            saved.add(memory);
        }
        public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
            if (fail) throw new IllegalStateException("simulated DB read failure");
            return rows;
        }
        LongTermMemoryEntity last() { return saved.get(saved.size() - 1); }
    }
}
