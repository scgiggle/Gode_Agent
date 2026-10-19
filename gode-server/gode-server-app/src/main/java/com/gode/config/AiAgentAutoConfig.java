package com.gode.config;

import com.gode.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.gode.domain.agent.service.IArmoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Slf4j
@Component
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配");

            armoryService.acceptArmoryAgents(new ArrayList<>(
                    aiAgentAutoConfigProperties.getTables().values()
            ));

        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
