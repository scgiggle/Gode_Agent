package com.gode.domain.agent.service.intent;

import com.gode.domain.agent.model.valobj.intent.ConversationContextVO;
import com.gode.domain.agent.model.valobj.intent.IntentResultVO;
import com.gode.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.gode.domain.agent.model.valobj.intent.TaskStateVO;
import com.gode.domain.agent.service.IIntentService;
import com.gode.domain.agent.service.intent.classifier.impl.LLMIntentClassifier;
import com.gode.domain.agent.service.intent.classifier.impl.RuleIntentClassifier;
import jakarta.annotation.Resource;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IntentService implements IIntentService {

    /**
     * 连续失败达到该次数后，后续分类跳过规则层、放宽 LLM 门槛
     */
    private static final int FAILURE_FALLBACK_THRESHOLD = 2;
    /**
     * 反馈回路触发重分类的置信度门槛：原意图低于此值才考虑重分类
     */
    private static final double FEEDBACK_RECLASSIFY_THRESHOLD = 0.7;

    @Resource
    private RuleIntentClassifier ruleClassifier;

    @Resource
    private LLMIntentClassifier llmClassifier;

    @Resource
    private ContextTracker contextTracker;

    /**
     * LRU 缓存：基于 LinkedHashMap 的 access-order 模式，最大 200 条目。
     * 缓存键为 sessionId+hash(message)，有效期 5 分钟，避免同一消息短时间内重复分类。
     */
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > 200;
                }
            });

    /** LRU 缓存条意图识别结果和过期时间戳 */
    private static class CacheEntry {
        /** 缓存的意图识别结果 */
        IntentResultVO result;

        /** 过期时间戳（毫秒），超过此时间后缓存失效 */
        long expireTime;

        CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }

    /**
     * 意图分类主入口：三层级联策略 + LRU 缓存。
     * <p>
     * 分类策略按优先级执行：
     * <ol>
     *   <li>缓存命中（5 分钟有效）→ 直接返回缓存结果</li>
     *   <li>连续失败 ≥2 → 跳过规则层，LLM 兜底（门槛放宽至 0.3）</li>
     *   <li>规则分类 → 置信度 ≥ 0.8 直接采用</li>
     *   <li>LLM 分类 → 置信度 ≥ 0.5 采用，否则回退规则结果</li>
     * </ol>
     * <p>
     * 分类结果同时写入 {@link ContextTracker}（意图历史、失败计数）并缓存，
     * 避免短时间内相同消息重复调用分类器。
     *
     * @param sessionId 会话 ID，用于区分不同用户的意图历史
     * @param userId    用户 ID（当前未用于分类逻辑，预留扩展）
     * @param message   用户原始消息
     * @return 意图识别结果，包含 intent、confidence、entities、candidates 等字段
     */
    @Override
    public IntentResultVO classify(String sessionId, String userId, String message) {
        // 缓存键：会话 + 消息哈希，5 分钟内同一消息直接复用结果，避免重复分类开销
        String cacheKey = sessionId + ":" + hashMessage(message);

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireTime > System.currentTimeMillis()) {
            return cached.result;
        }

        ConversationContextVO context = contextTracker.getContext(sessionId);
        int failures = contextTracker.getConsecutiveFailures(sessionId);

        IntentResultVO finalResult;

        // 连续失败次数过多：跳过规则层，直接 LLM 兜底，门槛放宽
        if (failures >= FAILURE_FALLBACK_THRESHOLD) {
            IntentResultVO llmResult = llmClassifier.classify(message, context);
            finalResult = llmResult.getConfidence() >= 0.3 ? llmResult : toUnknown(llmResult);
        } else {
            // 第1层：规则分类（< 1ms）
            IntentResultVO ruleResult = ruleClassifier.classify(message, context);
            if (ruleResult.getConfidence() >= 0.8) {
                finalResult = ruleResult;
            } else {
                // 第2层：LLM 分类（100-500ms）
                IntentResultVO llmResult = llmClassifier.classify(message, context);
                finalResult = llmResult.getConfidence() >= 0.5 ? llmResult : ruleResult;
            }
        }

        recordAndCache(sessionId, cacheKey, finalResult);
        return finalResult;
    }


    @Override
    public IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent,
                                         boolean success, String toolResult) {
        // 已重分类过的结果不再二次重分类，避免连锁递补
        if (lastIntent == null || lastIntent.isReclassified()) {
            return null;
        }

        // 仅在确认为失败时记录，避免一轮多工具结果重复累计
        if (!success) {
            contextTracker.recordFeedback(sessionId, false);
        }

        if (success) {
            return null;
        }

        // 高置信度意图不轻易重分类（可能只是工具偶发失败）
        if (lastIntent.getConfidence() >= FEEDBACK_RECLASSIFY_THRESHOLD) {
            return null;
        }

        // 工具结果明确提示"找不到/不存在" → 当前意图大概率走偏
        if (!looksLikeIntentMismatch(toolResult)) {
            return null;
        }

        // 用候选意图递补
        List<IntentTypeEnumVO> candidates = lastIntent.getCandidateIntents();
        if (candidates != null && !candidates.isEmpty()) {
            IntentResultVO reclassified = IntentResultVO.builder()
                    .intent(candidates.get(0))
                    .confidence(lastIntent.getConfidence() * 0.8)
                    .entities(lastIntent.getEntities())
                    .candidateIntents(candidates.size() > 1
                            ? new ArrayList<>(candidates.subList(1, candidates.size()))
                            : List.of())
                    .rawResponse(lastIntent.getRawResponse())
                    .reclassified(true)
                    .build();
            contextTracker.updateContext(sessionId, reclassified);
            return reclassified;
        }

        // 无候选 → UNKNOWN，全交主模型
        IntentResultVO unknown = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .confidence(0.0)
                .entities(lastIntent.getEntities())
                .candidateIntents(List.of())
                .reclassified(true)
                .build();
        contextTracker.updateContext(sessionId, unknown);
        return unknown;
    }

    public static boolean looksLikeIntentMismatch(String toolResult) {
        return toolResult != null && (
                toolResult.contains("not found")
                        || toolResult.contains("未找到")
                        || toolResult.contains("不存在")
                        || toolResult.contains("No such")
                        || toolResult.contains("command not found")
                        || toolResult.contains("Permission denied")
                        || toolResult.contains("Connection refused"));
    }

    @Override
    public TaskStateVO getTaskState(String sessionId) {
        return contextTracker.getTaskState(sessionId);
    }

    @Override
    public void updateTaskState(String sessionId, TaskStateVO taskState) {
        contextTracker.setTaskState(sessionId, taskState);
    }

    @Override
    public void configure(OpenAiApi openAiApi, String modelName) {
        llmClassifier.configure(openAiApi, modelName);
    }

    /**
     * 将分类结果写入上下文追踪器，并放入 LRU 缓存。
     * <p>
     * 缓存键为 {@code sessionId:hash(message)}，有效期 5 分钟，
     * 超过 200 条时自动淘汰最久未使用的条目。
     *
     * @param sessionId 会话 ID
     * @param cacheKey  缓存键
     * @param result    分类结果
     */
    private void recordAndCache(String sessionId, String cacheKey, IntentResultVO result) {
        contextTracker.updateContext(sessionId, result);
        // 缓存 5 分钟
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + 5 * 60 * 1000));
    }

    /**
     * 将消息转为缓存键用的哈希值（十六进制字符串）。
     * <p>
     * 使用 {@link String#hashCode()} 的十六进制表示，非加密哈希，
     * 仅用于缓存键拼接，不与消息原文一一对应。
     *
     * @param message 用户消息
     * @return 十六进制哈希字符串
     */
    private String hashMessage(String message) {
        return Integer.toHexString(message.hashCode());
    }

    /** 删除会话时同时清理意图历史、任务态和该会话的分类缓存。 */
    public void clearSession(String sessionId) {
        contextTracker.clear(sessionId);
        synchronized (cache) {
            cache.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        }
    }

    /**
     * 将分类结果降级为 UNKNOWN，用于连续失败后的兜底。
     * <p>
     * 保留原始结果的 entities 和 candidateIntents，方便主模型获取更多上下文。
     *
     * @param source 原始分类结果，可为 null
     * @return UNKNOWN 类型的 IntentResultVO
     */
    private IntentResultVO toUnknown(IntentResultVO source) {
        return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN)
                .confidence(0.0)
                .entities(source != null ? source.getEntities() : Map.of())
                .candidateIntents(source != null ? source.getCandidateIntents() : List.of())
                .rawResponse(source != null ? source.getRawResponse() : null)
                .build();
    }
}
