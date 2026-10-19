package com.gode.cases.react.node;

import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.react.AbstractAIAgentReActSupport;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.domain.agent.service.IChatContextService;
import com.gode.domain.agent.service.IPromptService;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IPromptService promptService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - 生成最终结果");

        String sessionId = dynamicContext.getSessionId();

        // 1. 构建最终结果
        ReActResultDTO result = dynamicContext.getResult();
        result.setFinalResponse(dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString() : "");

        // 2. 同步真实的统计数据
        result.setTotalSteps(dynamicContext.getStep());
        result.setTotalToolCalls(dynamicContext.getTotalToolCallCount().get());

        // 3. 将会话中实际执行的工具调用记录设置到结果中
        result.setToolCalls(dynamicContext.getExecutedToolCalls());

        // 4. 清理资源绑定和缓存
        chatContextService.clearSessionContext(sessionId);
        promptService.clearMilestones(sessionId);

        log.info("会话 {} 结束，总步数: {}, 总工具调用: {}, 状态: {}",
                sessionId, result.getTotalSteps(), result.getTotalToolCalls(), dynamicContext.getStopReason());

        return result;
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 这是链条最后一环
        return defaultStrategyHandler;
    }

}
