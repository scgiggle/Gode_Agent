package com.gode.domain.agent.service.mistake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.service.mistake.model.impl.OpenAiSubmissionReviewModel;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.support.GenericApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 真正执行 ADK Runner，模型 HTTP 请求由本地模拟服务响应，不调用外部模型。 */
public class SubmissionReviewRunnerSmokeTest {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String expected = "{\"errorSummary\":\"错因\",\"failReason\":\"边界\",\"aiAnalysis\":\"分析\",\"actionPlan\":\"检查\"}";
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var body = mapper.readTree(exchange.getRequestBody());
                check(body.path("model").asText().equals("review-test"), "复用配置模型名");
                check(!body.path("stream").asBoolean(), "复盘使用非流式最终回答");
                check(body.path("messages").size() == 2, "每次运行只有系统指令和本次用户输入，不混入历史");
                check(body.path("messages").get(0).path("content").asText().contains("JSON"), "系统指令进入 Agent instruction");
                String userMessage = body.path("messages").get(1).path("content").asText();
                check(userMessage.contains("submission"), "提交证据进入用户消息");
                check(body.path("tools").isMissingNode() || body.path("tools").isEmpty(), "无执行工具");
                calls.incrementAndGet();
                byte[] response = mapper.writeValueAsBytes(Map.of(
                        "id", "review-test", "object", "chat.completion", "created", 1,
                        "model", "review-test", "choices", List.of(Map.of(
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
            agent.setChatModelName("review-test");
            context.getBeanFactory().registerSingleton("demo-agent", agent);
            context.refresh();
            OpenAiSubmissionReviewModel model = new OpenAiSubmissionReviewModel();
            var field = OpenAiSubmissionReviewModel.class.getDeclaredField("applicationContext");
            field.setAccessible(true); field.set(model, context);
            check(expected.equals(model.analyze("demo-agent", "Only return JSON", "{\"submission\":\"first\"}")), "Runner 最终文本");
            check(expected.equals(model.analyze("demo-agent", "Only return JSON", "{\"submission\":\"second\"}")), "第二次运行隔离会话");
            if (failure.get() != null) throw new AssertionError("模拟模型请求断言失败", failure.get());
            check(calls.get() == 2, "两次分析分别调用模型");
            System.out.println("Submission review ADK Runner smoke checks passed");
        } finally { server.stop(0); }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
