package com.gode.domain.agent.service.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.suggestion.model.impl.AdkChatSuggestionModel;
import com.gode.types.exception.AppException;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.support.GenericApplicationContext;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 真正执行独立 ADK Runner，本地模拟模型 HTTP 服务响应，不使用真实密钥或付费模型。 */
public class ChatSuggestionRunnerSmokeTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String expected = "{\"suggestions\":[{\"label\":\"验证边界\",\"prompt\":\"请检查重复元素。\"}]}";
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stall = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var body = mapper.readTree(exchange.getRequestBody());
                check("suggestion-test".equals(body.path("model").asText()), "复用指定模型名");
                check(!body.path("stream").asBoolean(), "推荐非流式生成");
                check(body.path("messages").size() == 2, "每次只包含系统指令与本次上下文，无会话污染");
                check(body.path("messages").get(0).path("content").asText().contains("JSON"), "系统提示词进入 instruction");
                check(body.path("messages").get(1).path("content").asText().contains("two-sum"), "题目上下文进入用户消息");
                check(body.path("tools").isMissingNode() || body.path("tools").isEmpty(), "未挂载工具");
                calls.incrementAndGet();
                if (stall.get()) {
                    // 模拟供应商不返回，让真实 Rx timeout 生效，而不是直接抛异常。
                    release.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                byte[] response = mapper.writeValueAsBytes(Map.of(
                        "id", "suggestion-test", "object", "chat.completion", "created", 1,
                        "model", "suggestion-test", "choices", List.of(Map.of(
                                "index", 0, "message", Map.of("role", "assistant", "content", expected),
                                "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable error) {
                failure.set(error);
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AiAgentRegisterVO agent = new AiAgentRegisterVO();
            agent.setOpenAiApi(OpenAiApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("local-test-key").build());
            agent.setChatModelName("suggestion-test");
            context.getBeanFactory().registerSingleton("demo-agent", agent);
            context.getBeanFactory().registerSingleton("missing-config", new AiAgentRegisterVO());
            context.getBeanFactory().registerSingleton("wrong-type", new Object());
            context.refresh();
            AdkChatSuggestionModel model = new AdkChatSuggestionModel();
            inject(model, "applicationContext", context);
            check(expected.equals(model.generate("demo-agent", "Only return JSON", "{\"problemSlug\":\"two-sum\",\"turn\":1}")), "Runner 返回最终文本");
            check(expected.equals(model.generate("demo-agent", "Only return JSON", "{\"problemSlug\":\"two-sum\",\"turn\":2}")), "第二次调用独立会话");
            check(agent.getRunner() == null && "suggestion-test".equals(agent.getChatModelName()), "未重建或改写父 Agent 的 Runner 配置");
            expectBusinessFailure(() -> model.generate("not-found", "JSON", "{}"));
            expectBusinessFailure(() -> model.generate(null, "JSON", "{}"));
            expectBusinessFailure(() -> model.generate("wrong-type", "JSON", "{}"));
            expectBusinessFailure(() -> model.generate("missing-config", "JSON", "{}"));
            inject(model, "timeoutSeconds", 0L);
            try { model.generate("demo-agent", "JSON", "{}"); throw new AssertionError("invalid timeout"); }
            catch (IllegalStateException expectedError) { }
            if (failure.get() != null) throw new AssertionError("模拟模型请求断言失败", failure.get());
            check(calls.get() == 2, "两次有效调用分别发出独立模型请求，无效参数不请求网络");
            inject(model, "timeoutSeconds", 1L);
            stall.set(true);
            long started = System.nanoTime();
            try {
                model.generate("demo-agent", "Only return JSON", "{\"problemSlug\":\"two-sum\"}");
                throw new AssertionError("timeout did not fire");
            } catch (RuntimeException timeout) {
                Throwable cause = timeout;
                while (cause.getCause() != null) cause = cause.getCause();
                check(cause instanceof java.util.concurrent.TimeoutException, "真实 Runner 超时");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000, "及时结束等待");
            } finally { release.countDown(); }
            System.out.println("Chat suggestion ADK Runner smoke checks passed");
        } finally { release.countDown(); server.stop(0); }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    private static void expectBusinessFailure(Runnable action) {
        try { action.run(); throw new AssertionError("expected business exception"); }
        catch (AppException expected) { }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
