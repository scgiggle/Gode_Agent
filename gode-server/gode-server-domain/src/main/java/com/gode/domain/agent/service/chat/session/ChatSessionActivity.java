package com.gode.domain.agent.service.chat.session;

import com.gode.types.exception.AppException;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/** 同一会话同时只能运行一轮聊天或一次删除；不同会话互不阻塞。 */
@Component
public class ChatSessionActivity {
    private final ConcurrentHashMap<String, Lease> active = new ConcurrentHashMap<>();
    public Lease acquire(String sessionId) {
        Lease lease = new Lease(sessionId);
        if (active.putIfAbsent(sessionId, lease) != null) {
            throw new AppException("0002", "该会话仍在生成或处理中，请等待结束后重试");
        }
        return lease;
    }
    public final class Lease implements AutoCloseable {
        private final String sessionId;
        private Lease(String sessionId) { this.sessionId = sessionId; }
        @Override public void close() { active.remove(sessionId, this); }
    }
}
