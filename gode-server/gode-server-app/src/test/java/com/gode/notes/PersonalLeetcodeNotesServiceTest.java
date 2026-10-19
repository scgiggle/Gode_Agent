package com.gode.notes;

import com.gode.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.gode.domain.agent.service.armory.matter.mcp.FileSystemMcpToolsetFactory;
import com.gode.domain.agent.service.armory.matter.mcp.PersonalLeetcodeNotesService;
import com.gode.types.exception.AppException;
import com.google.adk.tools.BaseTool;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.mcp.McpToolset;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalLeetcodeNotesServiceTest {
    private final FileSystemMcpToolsetFactory factory = mock(FileSystemMcpToolsetFactory.class);
    private final McpToolset original = mock(McpToolset.class);
    private final McpToolset candidate = mock(McpToolset.class);
    private final McpToolset cleared = mock(McpToolset.class);

    private PersonalLeetcodeNotesService service(boolean enabled) {
        when(factory.create(any())).thenReturn(original, candidate, cleared);
        var config = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters();
        config.setName("leetcode-notes");
        config.setRequestTimeout(60000);
        config.setToolNames(List.of("get_problem", "get_note", "create_note", "update_note"));
        var parameters = new AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters();
        parameters.setCommand("test-mcp");
        parameters.setArgs(List.of("--site", "cn"));
        parameters.setEnv(Map.of("NODE_OPTIONS", "--max-old-space-size=96", "LEETCODE_SESSION", ""));
        config.setServerParameters(parameters);
        var service = new PersonalLeetcodeNotesService(factory, enabled);
        service.register("demo-agent", config);
        return service;
    }

    private BaseTool tool(String name, Map<String, Object> result) {
        var tool = mock(BaseTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn(name);
        when(tool.runAsync(anyMap(), isNull())).thenReturn(Single.just(result));
        return tool;
    }

    private List<BaseTool> validTools() {
        return List.of(tool("get_note", Map.of("text_output", List.of(Map.of("notes", List.of())))),
                tool("create_note", Map.of("success", true)), tool("update_note", Map.of("success", true)),
                tool("get_user_status", Map.of("text_output", List.of(Map.of("status", Map.of("isSignedIn", true))))));
    }

    @Test void savingValidatesReadOnlyAndReplacesToolsWithoutRebuildingAgent() {
        var service = service(true);
        List<BaseTool> tools = validTools();
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        var status = service.save("demo-agent", "LEETCODE_SESSION=test-cookie; csrftoken=ignored");
        assertTrue(status.verified());
        assertTrue(status.writeToolsAvailable());
        verify(tools.get(0)).runAsync(Map.of("questionId", "1", "limit", 1, "skip", 0), null);
        verify(tools.get(3)).runAsync(Map.of(), null);
        verify(tools.get(1), never()).runAsync(anyMap(), any());
        verify(tools.get(2), never()).runAsync(anyMap(), any());
        verify(original).close();
        assertEquals(List.of("get_note", "create_note", "update_note", "get_user_status"),
                service.getTools(null).map(BaseTool::name).toList().blockingGet());
        var configs = org.mockito.ArgumentCaptor.forClass(AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.class);
        verify(factory, times(2)).create(configs.capture());
        assertEquals("test-cookie", configs.getAllValues().get(1).getServerParameters().getEnv().get("LEETCODE_SESSION"));
        assertEquals("--max-old-space-size=96", configs.getAllValues().get(1).getServerParameters().getEnv().get("NODE_OPTIONS"));
        assertTrue(configs.getAllValues().get(1).getToolNames().contains("get_user_status"));
        assertFalse(status.toString().contains("test-cookie"));
    }

    @Test void missingWriteToolsKeepsPreviousConfigurationAndClosesCandidate() {
        var service = service(true);
        BaseTool problem = tool("get_problem", Map.of());
        when(candidate.getTools(isNull())).thenReturn(Flowable.just(problem));
        assertThrows(AppException.class, () -> service.save("demo-agent", "bad-cookie"));
        assertFalse(service.status("demo-agent").configured());
        verify(candidate).close();
        verify(original, never()).close();
    }

    @Test void nestedAuthenticationErrorIsNotMistakenForSuccessfulVerification() {
        var service = service(true);
        var read = tool("get_note", Map.of("text_output", List.of(Map.of("error", "cookie-secret"))));
        List<BaseTool> tools = List.of(read, tool("create_note", Map.of()), tool("update_note", Map.of()),
                tool("get_user_status", Map.of("status", Map.of("isSignedIn", true))));
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        AppException failure = assertThrows(AppException.class, () -> service.save("demo-agent", "cookie-secret"));
        assertFalse(failure.getInfo().contains("cookie-secret"));
        verify(candidate).close();
    }

    @Test void anExpiredCookieIsRejectedEvenWhenNotesWouldReturnAnEmptyList() {
        var service = service(true);
        var tools = validTools();
        when(tools.get(3).runAsync(anyMap(), isNull())).thenReturn(Single.just(
                Map.of("text_output", List.of(Map.of("status", Map.of("isSignedIn", false))))));
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        assertThrows(AppException.class, () -> service.save("demo-agent", "expired-cookie"));
        assertFalse(service.status("demo-agent").configured());
        verify(tools.get(0), never()).runAsync(anyMap(), any());
        verify(tools.get(1), never()).runAsync(anyMap(), any());
        verify(tools.get(2), never()).runAsync(anyMap(), any());
        verify(candidate).close();
        verify(original, never()).close();
    }

    @Test void clearingDisablesOldToolsAndDoesNotDeleteNotesOrReuseDefaultCookie() {
        var service = service(true);
        var tools = validTools();
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        service.save("demo-agent", "test-cookie");
        BaseTool oldWrite = service.getTools(null).filter(tool -> "create_note".equals(tool.name())).blockingFirst();
        assertFalse(service.clear("demo-agent").configured());
        assertTrue(oldWrite.runAsync(Map.of("content", "never written"), null).blockingGet().containsKey("error"));
        verify(tools.get(1), never()).runAsync(anyMap(), any());
        verify(candidate).close();
        var configs = org.mockito.ArgumentCaptor.forClass(AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.class);
        verify(factory, times(3)).create(configs.capture());
        assertEquals("", configs.getAllValues().get(2).getServerParameters().getEnv().get("LEETCODE_SESSION"));
    }

    @Test void disabledFeatureWrongAgentAndInvalidCookieAreRejectedBeforeLaunchingMcp() {
        var disabled = service(false);
        assertThrows(AppException.class, () -> disabled.save("demo-agent", "test-cookie"));
        verify(factory, times(1)).create(any());
        var anotherFactory = mock(FileSystemMcpToolsetFactory.class);
        var empty = new PersonalLeetcodeNotesService(anotherFactory, true);
        assertThrows(AppException.class, () -> empty.status("other-agent"));
        verifyNoInteractions(anotherFactory);
    }

    @Test void updatingCookieDoesNotGiveAnOlderInvocationToolsFromTheNewAccount() {
        var service = service(true);
        var read = tool("get_problem", Map.of());
        when(original.getTools(isNull())).thenReturn(Flowable.just(read));
        var context = mock(ReadonlyContext.class);
        when(context.invocationId()).thenReturn("old-invocation");
        assertEquals(1, service.getTools(context).toList().blockingGet().size());
        List<BaseTool> tools = validTools();
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        service.save("demo-agent", "new-account-cookie");
        assertThrows(AppException.class, () -> service.getTools(context).toList().blockingGet());
        var fresh = mock(ReadonlyContext.class);
        when(fresh.invocationId()).thenReturn("new-invocation");
        assertEquals(4, service.getTools(fresh).toList().blockingGet().size());
    }

    @Test void emptyMultilineAndMalformedCookiesDoNotStartCandidateProcesses() {
        var service = service(true);
        for (String cookie : List.of("", "   ", "secret\nheader", "{\"cookie\":\"value\"}")) {
            assertThrows(AppException.class, () -> service.save("demo-agent", cookie));
        }
        assertThrows(AppException.class, () -> service.save("wrong-agent", "test-cookie"));
        verify(factory, times(1)).create(any());
    }

    @Test void anActiveWriteFinishesBeforeClearAndStaleWritesAreBlocked() throws Exception {
        var service = service(true);
        var tools = validTools();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(tools.get(1).runAsync(anyMap(), isNull())).thenReturn(Single.fromCallable(() -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return Map.of("success", true);
        }));
        when(candidate.getTools(isNull())).thenReturn(Flowable.fromIterable(tools));
        service.save("demo-agent", "test-cookie");
        BaseTool write = service.getTools(null).filter(tool -> "create_note".equals(tool.name())).blockingFirst();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var active = executor.submit(() -> write.runAsync(Map.of("content", "explicit request"), null).blockingGet());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var clear = executor.submit(() -> service.clear("demo-agent"));
            verify(candidate, never()).close();
            release.countDown();
            assertEquals(true, active.get(3, TimeUnit.SECONDS).get("success"));
            assertFalse(clear.get(3, TimeUnit.SECONDS).configured());
            assertTrue(write.runAsync(Map.of(), null).blockingGet().containsKey("error"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
