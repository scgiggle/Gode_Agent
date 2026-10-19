package com.gode.domain.agent.service.prompt.dynamic;

import com.gode.domain.agent.model.valobj.prompt.MilestoneVO;
import com.gode.domain.agent.model.valobj.prompt.PromptContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DynamicPromptBuilder {

    /**
     * 将动态上下文追加到基础指令后面，拼成完整的 system instruction。
     * <p>
     * 适用于运行期可修改 system instruction 的场景。
     *
     * @param baseInstruction 基础系统指令文本
     * @param ctx             动态上下文，为 null 时直接返回基础指令
     * @return 拼接了环境信息、最近命令、里程碑事件的完整指令
     */
    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        // appendEnvironmentInfo(sb, ctx);
        appendTaskDescription(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendToolResultSummary(sb, ctx);
        appendLongTermMemorySummary(sb, ctx);
        // appendIntentLabel(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    public String buildMessageSuffix(PromptContextVO ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            sb.append("\n[最近执行的命令]\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        if (ctx.getMilestoneVOS() != null && !ctx.getMilestoneVOS().isEmpty()) {
            sb.append("\n[关键事件]\n");
            for (MilestoneVO m : ctx.getMilestoneVOS()) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        if (!isEmpty(ctx.getTaskDescription())) {
            sb.append("\n[当前任务]\n").append(ctx.getTaskDescription()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getToolResultSummary())) {
            sb.append("\n[工具执行摘要]\n").append(ctx.getToolResultSummary()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getLongTermMemorySummary())) {
            sb.append("\n[长期记忆]\n").append(ctx.getLongTermMemorySummary()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getIntentLabel())) {
            log.info("意图识别:{}", ctx.getIntentLabel());
            sb.append("\n[用户意图]\n").append(ctx.getIntentLabel()).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";

        String suffix = sb.toString();
        log.debug("构建消息后缀，长度: {}", suffix.length());
        return suffix;
    }

    /**
     * 追加当前任务描述段落（2-8 增强：优先从 TaskStateVO 获取，比从消息历史推断更准确）
     */
    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) {
            return;
        }
        sb.append("\n\n## 当前任务\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    /**
     * 追加工具执行摘要段落
     */
    private void appendToolResultSummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getToolResultSummary())) {
            return;
        }
        sb.append("\n\n## 工具执行摘要\n");
        sb.append(ctx.getToolResultSummary()).append("\n");
    }

    /**
     * <p>
     * 将 LongTermMemoryProvider 召回的长期记忆摘要渲染为 [长期记忆] 段落，
     * 拼到用户消息后面，让主模型感知用户偏好、环境信息、软件版本、排查经验等。
     */
    private void appendLongTermMemorySummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getLongTermMemorySummary())) {
            return;
        }
        sb.append("\n\n## 长期记忆\n");
        sb.append(ctx.getLongTermMemorySummary()).append("\n");
    }

    /**
     * 将最近执行的命令列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 命令列表为 null 或空时跳过。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    /**
     * 将里程碑事件列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 每条里程碑格式为：{@code [TYPE] content}
     * <p>
     * 案例：
     * <pre>
     *   ctx.getMilestoneVOS() = [
     *     { type=ERROR, content="permission denied" },
     *     { type=TASK_CHANGE, content="换个思路看 access.log" }
     *   ]
     *
     *   追加结果：
     *   "\n## 关键事件\n- [ERROR] permission denied\n- [TASK_CHANGE] 换个思路看 access.log\n"
     * </pre>
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

}
