package com.gode.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

public interface MessageReducer {

    /**
     * 裁剪消息历史
     *
     * @param messages    原始消息列表（按时间正序）
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表（保持时间正序）
     */
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);

}
