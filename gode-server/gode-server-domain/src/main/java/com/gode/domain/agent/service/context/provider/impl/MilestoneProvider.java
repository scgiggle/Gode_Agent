package com.gode.domain.agent.service.context.provider.impl;

import com.gode.domain.agent.model.valobj.prompt.MilestoneVO;
import com.gode.domain.agent.service.context.provider.ContextProvider;
import com.gode.domain.agent.service.prompt.dynamic.MilestoneTracker;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MilestoneProvider implements ContextProvider {

    @Resource
    private MilestoneTracker milestoneTracker;

    @Override
    public String getName() {
        return "milestoneVO";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);
        result.put("milestoneVOS", milestoneVOS);
        return result;
    }

}
