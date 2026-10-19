package com.gode.domain.agent.service.intent;

import com.gode.domain.agent.model.valobj.intent.ConversationContextVO;
import com.gode.domain.agent.model.valobj.intent.IntentHistoryEntryVO;
import com.gode.domain.agent.model.valobj.intent.IntentResultVO;
import com.gode.domain.agent.model.valobj.intent.TaskStateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ContextTracker {

    private static final int WINDOW_SIZE = 10;
    /** 会话上下文最大存活数量上限 */
    private static final int MAX_SESSIONS = 500;
    /** 会话过期时间：30 分钟无活跃则淘汰 */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, ConversationContextVO> contexts = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）会话上下文。
     * <p>
     * 若会话不存在则自动创建初始上下文，并触发惰性过期清理。
     * <p>
     * 案例：
     * <pre>
     *   sessionId = "session-001"
     *
     *   第1次调用：
     *   - 会话不存在，创建新上下文
     *   - 返回初始 ConversationContextVO：
     *     { recentIntents=[], turnCount=0, sessionStartTime=xxx, lastActiveTime=xxx, consecutiveFailures=0 }
     *
     *   第2次调用（5秒后）：
     *   - 会话已存在，直接返回
     *   - 触发 evictExpired() 清理过期会话
     * </pre>
     */
    public ConversationContextVO getContext(String sessionId) {
        evictExpired();
        return contexts.computeIfAbsent(sessionId, id -> {
            long now = System.currentTimeMillis();
            return ConversationContextVO.builder()
                    .recentIntents(new LinkedList<>())
                    .turnCount(0)
                    .sessionStartTime(now)
                    .lastActiveTime(now)
                    .consecutiveFailures(0)
                    .build();
        });
    }


    public void updateContext(String sessionId, IntentResultVO result) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.getRecentIntents().addLast(IntentHistoryEntryVO.builder()
                .intent(result.getIntent())
                .confidence(result.getConfidence())
                .timestamp(System.currentTimeMillis())
                .build());
        if (ctx.getRecentIntents().size() > WINDOW_SIZE) {
            ctx.getRecentIntents().removeFirst();
        }
        ctx.setTurnCount(ctx.getTurnCount() + 1);
        ctx.setLastIntent(result.getIntent());
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    public void recordFeedback(String sessionId, boolean success) {
        ConversationContextVO ctx = getContext(sessionId);
        if (success) {
            ctx.setConsecutiveFailures(0);
        } else {
            ctx.setConsecutiveFailures(ctx.getConsecutiveFailures() + 1);
        }
        if (ctx.getTaskState() != null) {
            ctx.getTaskState().setLastFeedbackFailed(!success);
        }
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    public int getConsecutiveFailures(String sessionId) {
        return getContext(sessionId).getConsecutiveFailures();
    }

    public TaskStateVO getTaskState(String sessionId) {
        return getContext(sessionId).getTaskState();
    }

    public void setTaskState(String sessionId, TaskStateVO taskState) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.setTaskState(taskState);
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    public void clear(String sessionId) {
        contexts.remove(sessionId);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConversationContextVO>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConversationContextVO> e = it.next();
            if (now - e.getValue().getLastActiveTime() > SESSION_TTL_MS) {
                it.remove();
            }
        }

        // 超出上限时按 lastActiveTime 升序淘汰（最旧的先移除）
        if (contexts.size() > MAX_SESSIONS) {
            contexts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) ->
                            Long.compare(a.getLastActiveTime(), b.getLastActiveTime())))
                    .limit(contexts.size() - MAX_SESSIONS)
                    .forEach(e -> contexts.remove(e.getKey()));
        }
    }

}
