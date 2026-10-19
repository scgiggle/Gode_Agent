package com.gode.domain.agent.service.prompt;

import com.gode.domain.agent.model.valobj.prompt.PromptContextVO;
import com.gode.domain.agent.service.IChatContextService;
import com.gode.domain.agent.service.IPromptService;
import com.gode.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import com.gode.domain.agent.service.prompt.dynamic.MilestoneTracker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 收集动态上下文，并将其追加到本轮用户消息末尾。
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    @Resource
    private MilestoneTracker milestoneTracker;

    @Resource
    private IChatContextService chatContextService;

    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(
            String userMessage,
            String sessionId,
            String userId,
            List<String> recentCommands,
            List<Map<String, Object>> messageHistory) {

        return buildEnrichedMessage(
                userMessage,
                sessionId,
                userId,
                recentCommands,
                messageHistory,
                null
        );
    }

    @Override
    public String buildEnrichedMessage(
            String userMessage,
            String sessionId,
            String userId,
            List<String> recentCommands,
            List<Map<String, Object>> messageHistory,
            String intentLabel) {

        PromptContextVO promptContext = chatContextService.buildPromptContext(
                sessionId,
                userId,
                messageHistory
        );
        promptContext.setRecentCommands(recentCommands);
        promptContext.setIntentLabel(intentLabel);

        String suffix = dynamicPromptBuilder.buildMessageSuffix(promptContext);
        if (suffix == null || suffix.isEmpty()) {
            return userMessage;
        }

        return userMessage + "\n---\n" + suffix;
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }
}
