package com.gode.domain.agent.service.intent.classifier.impl;

import com.gode.domain.agent.model.valobj.intent.ConversationContextVO;
import com.gode.domain.agent.model.valobj.intent.IntentResultVO;
import com.gode.domain.agent.model.valobj.intent.IntentRuleVO;
import com.gode.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.gode.domain.agent.service.intent.classifier.IIntentClassifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 算法学习场景的轻量规则分类；含歧义的低置信度结果仍交给 LLM 复核。 */
@Component
public class RuleIntentClassifier implements IIntentClassifier {
    private static final List<IntentRuleVO> RULES = List.of(
            rule(IntentTypeEnumVO.PROBLEM_ANALYSIS,
                    List.of("分析题目", "题目分析", "读题", "解题思路", "题意", "解题方法"),
                    List.of("(?:分析|讲解|理解).*(?:题目|这道题|两数之和)", "(?:怎么|如何).*(?:解题|做这道题)")),
            rule(IntentTypeEnumVO.HINT,
                    List.of("提示", "hint", "引导", "不剧透", "不要直接给答案"),
                    List.of("(?:只|先|给我).*(?:提示|一点思路)", "(?:别|不要|不用).*(?:完整代码|完整答案|直接给答案)")),
            rule(IntentTypeEnumVO.CODE_REVIEW,
                    List.of("代码检查", "检查代码", "调试", "debug", "报错", "判题", "提交失败", "失败用例",
                            "wrong answer", "wrong_answer", "runtime error", "compile error", "time limit exceeded", "memory limit exceeded"),
                    List.of("(?:代码|提交).*(?:错|失败|超时|越界|不通过|问题)", "(?:检查|分析|排查).*(?:代码|错误|用例)")),
            rule(IntentTypeEnumVO.SOLUTION,
                    List.of("完整代码", "完整答案", "完整解法", "标准答案", "参考答案", "直接给答案"),
                    List.of("(?:给我|提供|写出|生成).*(?:完整代码|完整答案|完整解法|标准答案|参考答案)", "直接.*(?:给|写).*(?:答案|代码)")),
            rule(IntentTypeEnumVO.COMPLEXITY,
                    List.of("时间复杂度", "空间复杂度", "复杂度", "优化", "降低复杂度", "complexity"),
                    List.of("(?:优化|加速).*(?:算法|代码|解法)", "(?:time|space) complexity")),
            rule(IntentTypeEnumVO.SIMILAR_PROBLEM,
                    List.of("相似题", "类似题", "同类题", "推荐题", "similar problem"),
                    List.of("推荐.*(?:题目|练习|道题)", "(?:找|练).*(?:相似|同类|类似).*题")),
            rule(IntentTypeEnumVO.NOTE,
                    List.of("笔记"), List.of("(?:读取|查看|保存|更新|写|删除).*笔记", "\\bnotes?\\b")),
            rule(IntentTypeEnumVO.REVIEW,
                    List.of("错题", "复盘", "复习", "学习计划", "薄弱点"),
                    List.of("总结.*(?:错误|教训|失败)", "(?:安排|制定).*复习")),
            rule(IntentTypeEnumVO.EXPLAIN,
                    List.of("什么意思", "怎么理解", "解释", "说明", "explain", "what is"),
                    List.of("(?:哈希表|双指针|二分|递归|动态规划|这行代码).*(?:意思|原理|作用)")),
            rule(IntentTypeEnumVO.SEARCH,
                    List.of("搜索文件", "查找文件", "读取文件", "列出文件", "文件目录"),
                    List.of("(?:找|搜索|查找|读取|列出).*(?:文件|目录)", "(?:search|read|list).*(?:file|directory)")),
            rule(IntentTypeEnumVO.CHAT, List.of(), List.of("^(?:你好|您好|谢谢|hello|hi)[!！。,.，\\s]*$"))
    );
    private static final double COMPOUND_THRESHOLD = 0.5;
    private static final Pattern CONTINUE = Pattern.compile("^(?:请|麻烦)?(?:继续|接着|往下|continue|go on)(?:吧|讲|说|一下|please)?[\\s,.，。！!？?]*$");
    private static final Pattern NO_SOLUTION = Pattern.compile("(?:不要|不用|别|不需要|不想|不提供|不输出|without|don't|do not)[^，。；;\\n]{0,24}(?:完整代码|完整答案|完整解法|标准答案|参考答案|直接给答案|full (?:code|solution))");

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        if (message == null || message.isBlank()) {
            return IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
        String lowerMsg = message.trim().toLowerCase(Locale.ROOT);
        if (CONTINUE.matcher(lowerMsg).matches()) {
            boolean ongoing = context != null && context.getTaskState() != null
                    && !context.getTaskState().isCompleted() && context.getTaskState().getRootIntent() != null;
            return IntentResultVO.builder().intent(IntentTypeEnumVO.CONTINUE).confidence(ongoing ? 0.95 : 0.4)
                    .entities(ongoing ? Map.of("task", String.valueOf(context.getTaskState().getCurrentStepIndex())) : Map.of())
                    .candidateIntents(ongoing ? List.of(context.getTaskState().getRootIntent()) : List.of()).build();
        }

        List<Map.Entry<IntentTypeEnumVO, Double>> scored = new ArrayList<>();
        for (IntentRuleVO rule : RULES) {
            // 否定的答案请求不能变成 SOLUTION；复杂句仍交给 LLM 复核。
            if (rule.getIntent() == IntentTypeEnumVO.SOLUTION && NO_SOLUTION.matcher(lowerMsg).find()) continue;
            long hits = rule.getKeywords().stream().filter(lowerMsg::contains).count();
            boolean patternHit = rule.getPatterns().stream()
                    .anyMatch(p -> Pattern.compile(p, Pattern.DOTALL).matcher(lowerMsg).find());
            if (hits == 0 && !patternHit) continue; // 历史只能加权，不能凭空产生本轮意图。
            double score = hits > 0 ? Math.min(0.7, 0.5 + (hits - 1) * 0.1) : 0.5;
            if (patternHit) score += 0.2;
            if (context != null && context.getRecentIntents() != null && context.getRecentIntents().stream()
                    .anyMatch(h -> h.getIntent() == rule.getIntent())) score += 0.1;
            scored.add(Map.entry(rule.getIntent(), Math.min(1.0, score)));
        }
        // “解释时间复杂度”是一个具体意图，不因通用“解释”一词误判为复合任务。
        boolean independentExplanation = Pattern.compile("(?:解释|说明|explain).{0,12}(?:哈希表|双指针|二分|递归|动态规划|概念|语法|原理)")
                .matcher(lowerMsg).find();
        if (!independentExplanation && scored.stream()
                .anyMatch(e -> e.getKey() != IntentTypeEnumVO.EXPLAIN && e.getKey() != IntentTypeEnumVO.CHAT)) {
            scored.removeIf(e -> e.getKey() == IntentTypeEnumVO.EXPLAIN);
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (scored.isEmpty()) {
            return IntentResultVO.builder().intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
        List<IntentTypeEnumVO> involved = scored.stream().filter(e -> e.getValue() >= COMPOUND_THRESHOLD)
                .map(Map.Entry::getKey).toList();
        boolean compound = involved.size() >= 2;
        return IntentResultVO.builder()
                .intent(compound ? IntentTypeEnumVO.COMPOUND : scored.get(0).getKey())
                // 规则只发现多个关键词，是否真正跨任务需要 LLM 判断；不绕过复核。
                .confidence(compound ? Math.min(0.75, scored.get(0).getValue()) : scored.get(0).getValue())
                .entities(extractEntities(lowerMsg))
                .candidateIntents(compound ? involved : scored.stream().skip(1).map(Map.Entry::getKey).toList()).build();
    }

    /** 只提取输入中明确出现的语言、题目 slug、知识点和判题状态，不猜测题号。 */
    private Map<String, String> extractEntities(String message) {
        Map<String, String> entities = new LinkedHashMap<>();
        Matcher language = Pattern.compile("(?<![a-z])(?:java(?:script)?|python(?:3)?|c\\+\\+|c#|typescript|golang|go)(?![a-z])").matcher(message);
        if (language.find()) entities.put("language", language.group());
        Matcher slug = Pattern.compile("(?:/problems/|(?:problem_?slug|title_?slug)\\s*[:=]\\s*[\"']?)([a-z0-9]+(?:-[a-z0-9]+)*)").matcher(message);
        if (slug.find()) entities.put("problemSlug", slug.group(1));
        List<String> topics = List.of("哈希表", "双指针", "滑动窗口", "二分", "动态规划", "回溯", "递归", "链表", "二叉树", "图论", "堆", "边界条件");
        String found = topics.stream().filter(message::contains).collect(java.util.stream.Collectors.joining(","));
        if (!found.isEmpty()) entities.put("topics", found);
        for (String verdict : List.of("wrong answer", "runtime error", "compile error", "time limit exceeded", "memory limit exceeded", "accepted")) {
            if (message.contains(verdict) || message.contains(verdict.replace(' ', '_'))) {
                entities.put("verdict", verdict.toUpperCase(Locale.ROOT).replace(' ', '_'));
                break;
            }
        }
        return entities;
    }

    private static IntentRuleVO rule(IntentTypeEnumVO intent, List<String> keywords, List<String> patterns) {
        IntentRuleVO rule = new IntentRuleVO();
        rule.setIntent(intent);
        rule.setKeywords(keywords);
        rule.setPatterns(patterns);
        return rule;
    }
}
