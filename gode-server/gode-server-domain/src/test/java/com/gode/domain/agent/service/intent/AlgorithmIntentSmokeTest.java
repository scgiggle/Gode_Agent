package com.gode.domain.agent.service.intent;

import com.gode.domain.agent.model.valobj.intent.*;
import com.gode.domain.agent.service.intent.classifier.impl.*;
import com.gode.domain.agent.service.armory.matter.tools.PlannerAgentBuilder;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 本地规则、提示词及模拟 LLM 回包回归；不请求真实模型。 */
public class AlgorithmIntentSmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        RuleIntentClassifier rule = new RuleIntentClassifier();
        expect(rule, "分析题目，讲解解题思路", IntentTypeEnumVO.PROBLEM_ANALYSIS);
        expect(rule, "给我一点提示，不要完整代码", IntentTypeEnumVO.HINT);
        expect(rule, "不用完整答案，先提示一下", IntentTypeEnumVO.HINT);
        expect(rule, "代码为什么越界", IntentTypeEnumVO.CODE_REVIEW);
        expect(rule, "请提供完整代码和完整解法", IntentTypeEnumVO.SOLUTION);
        expect(rule, "解释时间复杂度和空间复杂度", IntentTypeEnumVO.COMPLEXITY);
        expect(rule, "推荐三道相似题", IntentTypeEnumVO.SIMILAR_PROBLEM);
        expect(rule, "查看力扣笔记", IntentTypeEnumVO.NOTE);
        expect(rule, "安排错题复习", IntentTypeEnumVO.REVIEW);
        expect(rule, "解释哈希表的原理", IntentTypeEnumVO.EXPLAIN);
        expect(rule, "读取本地文件", IntentTypeEnumVO.SEARCH);
        expect(rule, "你好！", IntentTypeEnumVO.CHAT);
        expect(rule, "继续", IntentTypeEnumVO.CONTINUE);
        expect(rule, "继续分析代码", IntentTypeEnumVO.CODE_REVIEW);
        expect(rule, "继续推荐相似题", IntentTypeEnumVO.SIMILAR_PROBLEM);
        expect(rule, "部署 nginx 并重启 redis", IntentTypeEnumVO.UNKNOWN);
        expect(rule, null, IntentTypeEnumVO.UNKNOWN);
        expect(rule, " ", IntentTypeEnumVO.UNKNOWN);
        var compound = rule.classify("检查代码并推荐相似题", null);
        check(compound.getIntent() == IntentTypeEnumVO.COMPOUND, "多个独立操作");
        check(compound.getCandidateIntents().containsAll(List.of(IntentTypeEnumVO.CODE_REVIEW, IntentTypeEnumVO.SIMILAR_PROBLEM)), "复合任务列出具体意图");
        check(compound.getConfidence() < 0.8, "规则复合结果必须经过 LLM 复核");
        var explained = rule.classify("解释哈希表的原理，再推荐相似题", null);
        check(explained.getIntent() == IntentTypeEnumVO.COMPOUND
                && explained.getCandidateIntents().containsAll(List.of(IntentTypeEnumVO.EXPLAIN, IntentTypeEnumVO.SIMILAR_PROBLEM)), "独立概念解释不能被通用词去重吞掉");
        var entities = rule.classify("检查 Java 代码 Wrong Answer，https://leetcode.cn/problems/two-sum/，使用哈希表", null).getEntities();
        check("java".equals(entities.get("language")), "语言");
        check("two-sum".equals(entities.get("problemSlug")), "题目 slug");
        check("WRONG_ANSWER".equals(entities.get("verdict")), "状态");
        check("哈希表".equals(entities.get("topics")), "知识点");
        check(!entities.containsKey("service"), "无运维实体");
        var context = ConversationContextVO.builder().taskState(TaskStateVO.builder()
                .rootIntent(IntentTypeEnumVO.PROBLEM_ANALYSIS).currentStepIndex(2).build()).build();
        var continued = rule.classify("继续吧", context);
        check(continued.getConfidence() == 0.95 && continued.getCandidateIntents().contains(IntentTypeEnumVO.PROBLEM_ANALYSIS), "继续已有学习任务");

        LLMIntentClassifier llm = new LLMIntentClassifier();
        check(llm.classify("给一点提示", null).getIntent() == IntentTypeEnumVO.UNKNOWN, "模型未配置安全降级");
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>(
                "{\"intent\":\"HINT\",\"confidence\":0.96,\"entities\":{\"language\":\"java\"},\"candidates\":[]}");
        ChatModel mock = (ChatModel) Proxy.newProxyInstance(ChatModel.class.getClassLoader(), new Class[]{ChatModel.class},
                (proxy, method, arguments) -> {
                    if ("call".equals(method.getName())) {
                        prompt.set(String.valueOf(arguments[0]));
                        return response.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var field = LLMIntentClassifier.class.getDeclaredField("chatModel");
        field.setAccessible(true);
        field.set(llm, mock);
        check(llm.classify("给我一点提示，不要完整代码", null).getIntent() == IntentTypeEnumVO.HINT, "解析新意图");
        for (IntentTypeEnumVO type : IntentTypeEnumVO.values()) check(prompt.get().contains("- " + type.name() + ":"), "提示词包含 " + type);
        check(prompt.get().contains("不是 SOLUTION") && prompt.get().contains("只分类"), "分类与不剧透约束");
        check(!prompt.get().contains("SSH") && !prompt.get().contains("nginx"), "分类提示词无运维场景");
        response.set("{\"intent\":\"CODE_REVIEW\",\"confidence\":0.9,\"entities\":{\"language\":\"java\",\"nested\":{},\"number\":1},\"candidates\":[\"COMPLEXITY\",\"INVALID\"]}");
        var parsed = llm.classify("检查代码", null);
        check(parsed.getEntities().size() == 1 && "java".equals(parsed.getEntities().get("language")), "仅接受字符串实体");
        check(parsed.getCandidateIntents().equals(List.of(IntentTypeEnumVO.COMPLEXITY)), "候选新枚举与非法值过滤");
        response.set("{\"intent\":\"DEPLOY\",\"confidence\":0.9}");
        check(llm.classify("测试", null).getIntent() == IntentTypeEnumVO.UNKNOWN, "旧运维意图不接受");
        response.set("{\"intent\":\"HINT\",\"confidence\":2}");
        check(llm.classify("测试", null).getIntent() == IntentTypeEnumVO.UNKNOWN, "无效置信度降级");
        response.set("{\"intent\":\"UNKNOWN\",\"confidence\":0.9}");
        check(llm.classify("测试", null).getConfidence() == 0, "UNKNOWN 固定零置信度");
        response.set("不是 JSON");
        check(llm.classify("测试", null).getIntent() == IntentTypeEnumVO.UNKNOWN, "非法响应降级");
        check(llm.classify(null, null).getIntent() == IntentTypeEnumVO.UNKNOWN, "空输入");

        var instruction = PlannerAgentBuilder.class.getDeclaredField("INSTRUCTION");
        instruction.setAccessible(true);
        String planPrompt = (String) instruction.get(null);
        check(planPrompt.contains("\"tasks\"") && planPrompt.contains("\"dependsOn\"") && planPrompt.contains("\"maxConcurrency\""), "规划 JSON 契约保持不变");
        check(planPrompt.contains("最多5个任务") && planPrompt.contains("允许Agent列表"), "任务上限与允许 Agent");
        check(planPrompt.contains("不会把前一任务的结果自动传给后一任务") && planPrompt.contains("同一个任务"), "不虚构依赖结果传递");
        check(planPrompt.contains("不要完整代码") && planPrompt.contains("未明确授权"), "不剧透与写入授权边界");
        check(!planPrompt.contains("诊断") && !planPrompt.contains("变更任务"), "规划无运维任务");
        System.out.println("AlgorithmIntentSmokeTest: " + checks + " checks passed");
    }
    private static void expect(RuleIntentClassifier rule, String message, IntentTypeEnumVO expected) {
        check(rule.classify(message, null).getIntent() == expected, "分类 " + message + " -> " + expected);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
