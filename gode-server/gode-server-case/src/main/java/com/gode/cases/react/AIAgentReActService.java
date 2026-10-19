package com.gode.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.api.dto.ChatRequestDTO;
import com.gode.api.dto.ReActEventDTO;
import com.gode.api.dto.ReActResultDTO;
import com.gode.cases.IAIAgentReActServiceCase;
import com.gode.cases.react.factory.DefaultReActFactory;
import com.gode.cases.react.node.RootNode;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.domain.agent.service.chat.session.ChatSessionActivity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@Slf4j
@Service
public class AIAgentReActService implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private IChatSessionService chatSessionService;

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        // 1. 创建 SSE 发射器（3 分钟超时）
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);

        ChatSessionActivity.Lease lease = chatSessionService.beginMessage(
                requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage());
        try {
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId());

            // 2. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();

            // 3. 异步执行节点链路（避免阻塞 HTTP 线程）
            Thread streamThread = getThread(requestDTO, dynamicContext, emitter, lease);
            emitter.onTimeout(streamThread::interrupt);
            emitter.onError(error -> streamThread.interrupt());
            streamThread.start();

        } catch (Exception e) {
            lease.close();
            log.error("ReAct 流式对话初始化失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @NotNull
    private Thread getThread(ChatRequestDTO requestDTO, DefaultReActFactory.DynamicContext dynamicContext, ResponseBodyEmitter emitter, ChatSessionActivity.Lease lease) {
        Thread streamThread = new Thread(() -> {
            try {
                ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
                log.info("ReAct 流式对话完成 - 步数:{}, 工具调用:{}, stopReason:{}",
                        result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());

                ReActEventDTO done = new ReActEventDTO();
                done.setEvent("done");
                done.setContent(result.getFinalResponse());
                emitter.send(objectMapper.writeValueAsString(done) + "\n");
                emitter.complete();
            } catch (Exception e) {
                log.error("ReAct 流式对话异常", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            } finally {
                lease.close();
            }
        }, "react-stream-" + requestDTO.getSessionId());
        streamThread.setDaemon(true);
        return streamThread;
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try (ChatSessionActivity.Lease ignored = chatSessionService.beginMessage(
                requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage())) {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new ResponseBodyEmitter(60 * 1000L))
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            return result.getFinalResponse();

        } catch (Exception e) {
            log.error("ReAct 普通对话异常", e);
            return "Error: " + e.getMessage();
        }
    }
}
