package com.gode.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.service.chat.session.ChatSessionService;
import com.gode.infrastructure.dao.IChatMessageDao;
import com.gode.infrastructure.dao.IChatSessionDao;
import com.gode.infrastructure.dao.adapter.repository.ChatHistoryRepository;
import com.gode.infrastructure.dao.po.ChatMessagePO;
import com.gode.infrastructure.dao.po.ChatSessionPO;
import com.gode.trigger.http.ChatSessionController;
import com.gode.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ChatMessageAppendTest {
    @Test void savesOrdinaryAssistantMessagesAndRestoresBothIdenticalMessages() throws Exception {
        Fixture f = new Fixture();
        ChatSessionController controller = new ChatSessionController();
        inject(controller, "chatSessionService", f.service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        ObjectMapper mapper = new ObjectMapper();
        String content = "已连接《两数之和》。";
        String body = mapper.writeValueAsString(Map.of("agentId", "demo", "userId", "u", "sessionId", "s", "content", content));
        for (int i = 1; i <= 2; i++) {
            var response = mvc.perform(post("/api/v1/chat/message").contentType("application/json").content(body)).andReturn().getResponse();
            var saved = mapper.readTree(response.getContentAsByteArray());
            assertEquals("0000", saved.path("code").asText());
            assertEquals(i, saved.path("data").path("id").asLong());
            assertEquals("assistant", saved.path("data").path("role").asText());
            assertEquals(content, saved.path("data").path("content").asText());
        }
        var response = mvc.perform(get("/api/v1/chat/session_detail").param("agentId", "demo").param("userId", "u").param("sessionId", "s"))
                .andReturn().getResponse();
        var history = mapper.readTree(response.getContentAsByteArray()).path("data");
        assertEquals(2, history.path("messages").size());
        assertEquals(2, history.path("session").path("messageCount").asInt());
        assertEquals(content, history.path("messages").get(0).path("content").asText());
        assertEquals(content, history.path("messages").get(1).path("content").asText());
        verify(f.sessions, never()).updateDefaultTitle(anyString(), anyString());
    }

    @Test void rejectsWrongOwnerAndBlankContentWithoutSaving() throws Exception {
        Fixture f = new Fixture();
        assertThrows(AppException.class, () -> f.service.appendAssistantMessage("demo", "other", "s", "消息"));
        assertThrows(AppException.class, () -> f.service.appendAssistantMessage("demo", "u", "s", " "));
        assertTrue(f.rows.isEmpty());
    }

    @Test void propagatesDatabaseFailureInsteadOfReportingSuccess() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("DB failure")).when(f.messages).insert(any());
        assertThrows(IllegalStateException.class, () -> f.service.appendAssistantMessage("demo", "u", "s", "消息"));
        verify(f.sessions, never()).updateMessageCount(anyString());
    }

    private static final class Fixture {
        final IChatSessionDao sessions = mock(IChatSessionDao.class);
        final IChatMessageDao messages = mock(IChatMessageDao.class);
        final List<ChatMessagePO> rows = new ArrayList<>();
        final ChatSessionService service = new ChatSessionService();
        Fixture() throws Exception {
            var session = ChatSessionPO.builder().id("s").agentId("demo").userId("u").title("新对话").messageCount(0).build();
            when(sessions.queryOwnedSession("demo", "u", "s")).thenReturn(session);
            when(messages.queryVisibleMessagePage(eq("s"), isNull(), anyInt())).thenAnswer(call ->
                    rows.stream().sorted(Comparator.comparing(ChatMessagePO::getId).reversed()).toList());
            doAnswer(call -> { ChatMessagePO row = call.getArgument(0); row.setId((long) rows.size() + 1); rows.add(row); return null; })
                    .when(messages).insert(any());
            doAnswer(call -> { session.setMessageCount(session.getMessageCount() + 1); return null; }).when(sessions).updateMessageCount("s");
            ChatHistoryRepository repository = new ChatHistoryRepository();
            inject(repository, "chatSessionDao", sessions); inject(repository, "chatMessageDao", messages);
            inject(service, "chatHistoryRepository", repository);
        }
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        var member = target.getClass().getDeclaredField(field); member.setAccessible(true); member.set(target, value);
    }
}
