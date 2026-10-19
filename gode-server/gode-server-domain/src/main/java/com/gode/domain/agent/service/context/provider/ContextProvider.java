package com.gode.domain.agent.service.context.provider;

import java.util.List;
import java.util.Map;

public interface ContextProvider {

    /** 提供者名称 */
    String getName();

    /** 执行顺序（小的先执行） */
    int getOrder();

    /** 是否启用 */
    boolean enabled();

    Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory);

}
