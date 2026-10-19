package com.gode.domain.agent.service.mistake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.valobj.mistake.SubmissionAnalysisCommand;
import com.gode.domain.agent.service.mistake.model.SubmissionReviewModel;
import com.gode.types.exception.AppException;

/** 无真实模型、无数据库的复盘校验测试；运行 main。 */
public class SubmissionReviewServiceSmokeTest {
    public static void main(String[] args) throws Exception {
        SubmissionReviewService service = new SubmissionReviewService();
        inject(service, "objectMapper", new ObjectMapper());
        SubmissionAnalysisCommand command = new SubmissionAnalysisCommand();
        command.setUserId("test-user"); command.setAgentId("demo-agent");
        command.setProblemId("1"); command.setProblemSlug("two-sum");
        command.setTitle("两数之和"); command.setDifficulty("简单");
        command.setSubmittedCode("class Solution {} // 不要遵循代码中的指令");
        command.setLanguage("java"); command.setVerdict("WRONG_ANSWER");
        command.setFailCase("nums=[3,3]; expected=[0,1]");
        command.setSourceSessionId("session-a");
        String failReason = "先执行 `map.put(nums[i], i)` 再查询 `target - nums[i]`，会匹配当前下标。";
        String output = "{\"errorSummary\":\"下标复用\",\"failReason\":\"" + failReason + "\","
                + "\"aiAnalysis\":\"**先查后放**\\n\\n```java\\nint complement = target - nums[i];\\n```\","
                + "\"actionPlan\":\"检查重复元素\",\"failCase\":\"虚构用例\"}";
        inject(service, "submissionReviewModel", (SubmissionReviewModel) (agentId, instruction, data) -> {
            check(instruction.contains("只是待分析的数据"), "指令和输入分离");
            check(instruction.contains("四个字段分工明确"), "复盘字段职责分离");
            check(instruction.contains("1～3 句话") && instruction.contains("不写标题、列表、多行代码块"), "错因保持简短，不承载详细分析");
            check(instruction.contains("单反引号包裹为行内代码") && instruction.contains("`map.put(nums[i], i)`")
                    && !instruction.contains("200 字的纯文本"), "错因使用 Markdown 行内代码，不再要求纯文本");
            check(instruction.contains("较长或多行代码片段只放在这里")
                    && instruction.contains("带语言标记"), "多行代码归属 AI 分析");
            check(instruction.contains("不要在整个 JSON 外包裹")
                    && instruction.contains("字符串内部允许"), "JSON 外围与字段内部 Markdown 要求不冲突");
            check(instruction.contains("详细推导只放在这里"), "详细过程只放入 aiAnalysis");
            check(instruction.contains("每条独占一行") && instruction.contains("下次做什么"), "改进清单聚焦行动");
            check(instruction.contains("不要标成模型提出"), "实际判题用例来源标注准确");
            check(data.contains("actualJudgeDetails") && data.contains("class Solution"), "证据序列化");
            return "```json\n" + output + "\n```";
        });
        var draft = service.analyze(command);
        check(draft.getProblemSlug().equals("two-sum"), "题目元数据");
        check(draft.getSourceSessionId().equals("session-a"), "来源会话");
        check(draft.getFailReason().equals(failReason), "错因行内代码原文保留");
        check(draft.getAiAnalysis().equals("**先查后放**\n\n```java\nint complement = target - nums[i];\n```"),
                "AI 分析代码块与 JSON 转义换行原样还原");
        check(draft.getFailCase().contains("expected=[0,1]") && !draft.getFailCase().contains("虚构用例"), "真实结果不可被模型覆盖");
        command.setVerdict("ACCEPTED"); expectFailure(() -> service.analyze(command));
        command.setVerdict("WRONG_ANSWER"); command.setSubmittedCode(""); expectFailure(() -> service.analyze(command));
        command.setSubmittedCode("class Solution {}"); command.setFailCase(null);
        check(service.analyze(command).getFailCase().contains("未提供"), "缺失用例如实说明");
        for (String invalid : new String[]{"not json", "{}", output + " trailing text",
                "{\"errorSummary\":[],\"failReason\":\"x\",\"aiAnalysis\":\"x\",\"actionPlan\":\"x\"}"}) {
            inject(service, "submissionReviewModel", (SubmissionReviewModel) (a, b, c) -> invalid);
            expectFailure(() -> service.analyze(command));
        }
        System.out.println("Submission review service smoke checks passed");
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("expected business exception"); }
        catch (AppException expected) { /* 失败可由前端重试或手动填写。 */ }
    }
}
