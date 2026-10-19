package com.gode.domain.agent.service.chat;

import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.gode.domain.agent.service.IChatService;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatSessionService chatSessionService;

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        return chatSessionService.createSession(agentId, userId, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        return Flowable.defer(() -> {
            var lease = chatSessionService.beginMessage(agentId, userId, sessionId, message);
            try {
                return runner.runAsync(userId, sessionId, userMsg, runConfig).doFinally(lease::close);
            } catch (RuntimeException e) {
                lease.close();
                throw e;
            }
        });
    }


}
