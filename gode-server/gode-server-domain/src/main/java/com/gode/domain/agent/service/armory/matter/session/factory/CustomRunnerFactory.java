package com.gode.domain.agent.service.armory.matter.session.factory;

import com.gode.domain.agent.service.armory.matter.session.CustomAdkMemoryService;
import com.gode.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CustomRunnerFactory {

    @Resource
    private CustomAdkSessionService customAdkSessionService;

    @Resource
    private CustomAdkMemoryService customAdkMemoryService;

    public Runner create(BaseAgent baseAgent, String appName, List<BasePlugin> plugins) {

//        Runner.builder()
//                .agent(baseAgent)
//                .appName(appName)
//                .artifactService(new InMemoryArtifactService())
//                .sessionService(customAdkSessionService)
//                .memoryService(customAdkMemoryService)
//                .plugins(plugins)
//                .build();

        return new Runner(
                baseAgent,
                appName,
                new InMemoryArtifactService(),
                customAdkSessionService,
                customAdkMemoryService,
                plugins
        );
    }
}
