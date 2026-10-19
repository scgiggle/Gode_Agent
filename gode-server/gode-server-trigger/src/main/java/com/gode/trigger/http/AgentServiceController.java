package com.gode.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.api.IAgentService;
import com.gode.api.dto.*;
import com.gode.api.response.Response;
import com.gode.cases.IAIAgentReActServiceCase;
import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.service.IArmoryService;
import com.gode.domain.agent.service.IChatService;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private IChatSessionService chatSessionService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST, produces = "application/json")
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatSessionService.createSession(requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getTitle());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("创建会话异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = "application/x-ndjson")
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        String sessionId = requestDTO.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new AppException("0002", "请先创建对话并传入sessionId");
        }

        // 最长等待 10 分钟
        ResponseBodyEmitter emitter =
                new ResponseBodyEmitter(10 * 60 * 1000L);

        // 获取 ADK 的事件流，并把每个事件持续发给前端
        chatService.handleMessageStream(
                requestDTO.getAgentId(),
                requestDTO.getUserId(),
                sessionId,
                requestDTO.getMessage()
        ).subscribe(
                event -> {
                    String agentName = event.author();
                    String content = event.stringifyContent();

                    if (content == null || content.isBlank()) {
                        return;
                    }

                    ChatStreamEventDTO streamEvent =
                            new ChatStreamEventDTO(
                                    "chunk",
                                    agentName,
                                    content
                            );

                    emitter.send(
                            objectMapper.writeValueAsString(streamEvent) + "\n"
                    );
                },
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }


    @PostMapping(value = "chat_react_stream", produces = "application/x-ndjson")
    @Override
    public ResponseBodyEmitter chatReActStream(
            @RequestBody ChatRequestDTO requestDTO) {

        String sessionId = requestDTO.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new AppException("0002", "请先创建对话并传入sessionId");
        }

        return reactServiceCase.chatStream(requestDTO);
    }

}
