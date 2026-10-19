package com.gode.domain.agent.service.armory.node;

import com.gode.domain.agent.model.entity.ArmoryCommandEntity;
import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.armory.AbstractArmorySupport;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AiAgentConfigTableVO config =
                requestParameter.getAiAgentConfigTableVO();

        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = config.getModule().getChatModel();


        String modelName = config.getModule()
                .getChatModel()
                .getModel();

        if (dynamicContext.getOpenAiApi() == null) {
            throw new IllegalStateException(
                    "ChatModelNode 装配失败：OpenAiApi 尚未创建");
        }

        // 收集需要注册给模型的 Spring AI 工具
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills>
                toolSkillsList = chatModelConfig.getToolSkillsList();

        // 根据 YAML 配置加载 Skill
        if (toolSkillsList != null && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills
                    : toolSkillsList) {

                ToolCallback[] callbacks =
                        toolSkillsCreateService.buildToolCallback(toolSkills);

                toolCallbackList.addAll(List.of(callbacks));

                log.info(
                        "[ChatModelNode] Skill 加载完成 type={} path={} callbackCount={}",
                        toolSkills.getType(),
                        toolSkills.getPath(),
                        callbacks.length
                );
            }
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(dynamicContext.getOpenAiApi())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .toolCallbacks(toolCallbackList)
                        .build())
                .build();

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
