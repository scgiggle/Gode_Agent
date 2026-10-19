package com.gode.domain.agent.service.armory.node;

import com.gode.domain.agent.model.entity.ArmoryCommandEntity;
import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.armory.AbstractArmorySupport;
import com.gode.domain.agent.service.armory.catalog.AgentCatalog;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import com.gode.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {

    @Resource
    private CustomRunnerFactory customRunnerFactory;

    @Resource
    private AgentCatalog agentCatalog;

    @Override
    protected AiAgentRegisterVO doApply(
            ArmoryCommandEntity requestParameter,
            DefaultArmoryFactory.DynamicContext dynamicContext) {

        log.info("Ai Agent 装配操作 - RunnerNode");
        AiAgentConfigTableVO table =
                requestParameter.getAiAgentConfigTableVO();

        AiAgentConfigTableVO.Module.Runner runnerConfig =
                table.getModule().getRunner();

        if (runnerConfig == null || runnerConfig.getAgentName() == null) {
            throw new IllegalArgumentException(
                    "RunnerNode 装配失败：runner.agentName 不能为空");
        }

        String runnerAgentName = runnerConfig.getAgentName();

        BaseAgent baseAgent = dynamicContext.getAgentGroup()
                .get(runnerAgentName);

        if (baseAgent == null) {
            throw new IllegalStateException(
                    "RunnerNode 装配失败：找不到 Agent - " + runnerAgentName);
        }

        Runner runner = customRunnerFactory.create(
                baseAgent,
                table.getAppName(),
                List.of()
        );

        agentCatalog.register(
                table.getAgent().getAgentId(),
                dynamicContext.getAgentGroup()
        );

        dynamicContext.setRunner(runner);

        AiAgentRegisterVO registerVO = AiAgentRegisterVO.builder()
                .appName(table.getAppName())
                .agentId(table.getAgent().getAgentId())
                .agentName(table.getAgent().getAgentName())
                .agentDesc(table.getAgent().getAgentDesc())
                .runner(runner)
                .openAiApi(dynamicContext.getOpenAiApi())
                .chatModelName(table.getModule().getChatModel().getModel())
                .build();

        // 放入 Factory 注册表
        // defaultArmoryFactory.registerAgent(registerVO);

        // 同时按 agentId 动态注册为 Spring Bean
        registerBean(
                registerVO.getAgentId(),
                AiAgentRegisterVO.class,
                registerVO
        );

        return registerVO;
    }


    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
