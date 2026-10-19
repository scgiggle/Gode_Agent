package com.gode.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActEventDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;


@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 通用的 Bean 获取
     */
    protected <T> T getBean(String beanName) {
        return applicationContext.getBean(beanName, (Class<T>) Object.class);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送文本事件
     */
    protected void sendTextEvent(ResponseBodyEmitter emitter, String content, String fullText) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("text");
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送文本事件 {}", event);
        } catch (Exception e) {
            log.warn("发送文本事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具调用事件
     */
    protected void sendToolCallEvent(ResponseBodyEmitter emitter, String toolCallId, String toolName, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_call");
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具调用事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具调用事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具结果事件
     */
    protected void sendToolResultEvent(ResponseBodyEmitter emitter, String toolCallId, String content, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_result");
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具结果事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具结果事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送步数结束事件
     */
    protected void sendRoundEndEvent(ResponseBodyEmitter emitter, int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setShouldContinue(shouldContinue);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_end");
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 round_end 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 round_end 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送完成事件
     */
    protected void sendDoneEvent(ResponseBodyEmitter emitter, ReActResultDTO result) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("done");
            event.setContent(objectMapper.writeValueAsString(result));
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 done 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 done 事件失败: {}", e.getMessage());
        }
    }


}
