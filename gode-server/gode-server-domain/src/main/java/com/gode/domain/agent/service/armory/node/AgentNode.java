package com.gode.domain.agent.service.armory.node;

import com.gode.domain.agent.model.entity.ArmoryCommandEntity;
import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.armory.AbstractArmorySupport;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.armory.matter.mcp.FileSystemMcpToolsetFactory;
import com.gode.domain.agent.service.armory.matter.mcp.PersonalLeetcodeNotesService;
import com.gode.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import com.gode.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import com.gode.domain.agent.service.armory.matter.tools.*;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.mcp.McpToolset;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private RunnerNode runnerNode;

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private SimilarProblemTool similarProblemTool;

    @Resource
    private FileSystemMcpToolsetFactory fileSystemMcpToolsetFactory;

    @Resource
    private PersonalLeetcodeNotesService personalLeetcodeNotesService;

    @Resource
    private CustomRunnerFactory customRunnerFactory;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;

    @Resource
    private PlanParser planParser;

    @Resource
    private PlanValidator planValidator;


    @Override
    protected AiAgentRegisterVO doApply(
            ArmoryCommandEntity requestParameter,
            DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        log.info("Ai Agent 装配操作 - AgentNode");
        AiAgentConfigTableVO config = requestParameter.getAiAgentConfigTableVO();

        List<AiAgentConfigTableVO.Module.Agent> agents = config.getModule().getAgents();

        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException(
                    "AgentNode 装配失败：agents 配置不能为空");
        }

        if (dynamicContext.getChatModel() == null) {
            throw new IllegalStateException(
                    "AgentNode 装配失败：ChatModel 尚未创建");
        }

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new SpringAI(dynamicContext.getChatModel()))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            if ("toolRecommend".equals(
                    agentConfig.getName())) {

                FunctionTool recommendTool =
                        FunctionTool.create(
                                similarProblemTool,
                                "recommendSimilarProblems"
                        );

                builder.tools(List.of(recommendTool));
            }

            if ("fileAssistant".equals(agentConfig.getName())) {

                List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp>
                        toolMcpList = config.getModule()
                        .getChatModel()
                        .getToolMcpList();

                if (toolMcpList == null || toolMcpList.isEmpty()) {
                    throw new IllegalStateException(
                            "fileAssistant没有配置MCP服务"
                    );
                }

                AiAgentConfigTableVO.Module.ChatModel.ToolMcp
                        .StdioServerParameters filesystemConfig =
                        toolMcpList.stream()
                                .map(AiAgentConfigTableVO.Module
                                        .ChatModel.ToolMcp::getStdio)
                                .filter(Objects::nonNull)
                                .filter(item ->
                                        "filesystem".equals(item.getName())
                                )
                                .findFirst()
                                .orElseThrow(() ->
                                        new IllegalStateException(
                                                "没有找到filesystem MCP配置"
                                        )
                                );

                McpToolset filesystemToolset =
                        fileSystemMcpToolsetFactory.create(
                                filesystemConfig
                        );

                builder.tools(List.of(filesystemToolset));

                log.info(
                        "[AgentNode] 已为Agent注册Filesystem MCP agentName={}",
                        agentConfig.getName()
                );
            }

            if ("leetcodeNoteAssistant".equals(agentConfig.getName())) {

                List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList =
                        config.getModule()
                                .getChatModel()
                                .getToolMcpList();

                AiAgentConfigTableVO.Module.ChatModel.ToolMcp
                        .StdioServerParameters leetcodeConfig =
                        toolMcpList.stream()
                                .map(AiAgentConfigTableVO.Module
                                        .ChatModel.ToolMcp::getStdio)
                                .filter(Objects::nonNull)
                                .filter(item ->
                                        "leetcode-notes".equals(item.getName()))
                                .findFirst()
                                .orElseThrow(() ->
                                        new IllegalStateException(
                                                "没有找到 leetcode-notes MCP 配置"
                                        )
                                );

                builder.tools(List.of(personalLeetcodeNotesService.register(
                        config.getAgent().getAgentId(), leetcodeConfig)));

                log.info(
                        "[AgentNode] 已为 Agent 注册 LeetCode Notes MCP agentName={}",
                        agentConfig.getName()
                );
            }



            LlmAgent llmAgent = builder.build();

            dynamicContext.getAgentGroup().put(
                    agentConfig.getName(),
                    llmAgent
            );
        }

        buildAgentTools(
                dynamicContext,
                agents,
                dynamicContext.getChatModel(),
                config.getModule().getChatModel().getModel()
        );

        return router(requestParameter, dynamicContext);
    }

    private void buildAgentTools(
            DefaultArmoryFactory.DynamicContext dynamicContext,
            List<AiAgentConfigTableVO.Module.Agent> agents,
            ChatModel chatModel,
            String modelName) {

        Map<String, BaseAgent> agentGroup =
                dynamicContext.getAgentGroup();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {

            List<String> subAgentNames =
                    agentConfig.getSubAgents();

            // 没有配置 sub-agents，说明它不是父 Agent
            if (subAgentNames == null || subAgentNames.isEmpty()) {
                continue;
            }

            List<String> allowedAgents =
                    List.copyOf(subAgentNames);

            List<Object> dispatchTools =
                    new ArrayList<>();

            // 将每个子 Agent包装成一个可以被父 Agent调用的工具
            for (String subAgentName : subAgentNames) {

                if (agentConfig.getName().equals(subAgentName)) {
                    throw new IllegalArgumentException(
                            "父 Agent 不能把自己配置为子 Agent: "
                                    + subAgentName
                    );
                }

                BaseAgent subAgent =
                        agentGroup.get(subAgentName);

                if (subAgent == null) {
                    throw new IllegalArgumentException(
                            "没有找到子 Agent: "
                                    + subAgentName
                    );
                }

                dispatchTools.add(
                        new SubAgentDispatchTool(
                                subAgent,
                                customRunnerFactory
                        )
                );
            }

            // 父 Agent自己生成任务列表，然后批量执行
            dispatchTools.add(
                    new BatchSubAgentDispatchTool(
                            allowedAgents,
                            dynamicAgentOrchestrator
                    )
            );

            // 使用独立规划 Agent拆分任务，然后执行
            dispatchTools.add(
                    new DynamicPlanDispatchTool(
                            plannerAgentBuilder,
                            dynamicAgentOrchestrator,
                            planParser,
                            planValidator,
                            dynamicContext.getOpenAiApi(),
                            modelName,
                            allowedAgents
                    )
            );

            // 重新创建父 Agent，为它注册派发工具
            LlmAgent parentAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new SpringAI(chatModel))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(dispatchTools)
                    .build();

            // 覆盖第一遍创建的普通父 Agent
            agentGroup.put(
                    agentConfig.getName(),
                    parentAgent
            );

            log.info(
                    "[AgentNode] 父Agent派发工具装配完成 parentAgent={} subAgents={} toolCount={}",
                    agentConfig.getName(),
                    subAgentNames,
                    dispatchTools.size()
            );
        }
    }


    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {

        return agentWorkflowNode;
    }

}
