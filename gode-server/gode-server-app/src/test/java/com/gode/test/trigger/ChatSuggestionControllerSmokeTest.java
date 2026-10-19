package com.gode.test.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.service.suggestion.ChatSuggestionService;
import com.gode.domain.agent.service.suggestion.model.ChatSuggestionModel;
import com.gode.trigger.http.ChatSuggestionController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 Controller + Service 的 HTTP 映射检查，模型为本地替身，不调用付费 API。 */
public class ChatSuggestionControllerSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST = """
            {"agentId":"demo-agent","userId":"extension-test","sessionId":"session-a",
             "problemSlug":"two-sum","problemTitle":"两数之和",
             "messages":[null,{"role":"user","content":"为什么不能先插入？"},
                         {"role":"assistant","content":"可能与自己匹配。"}]}
            """;
    private static final String MODEL_RESPONSE = """
            {"suggestions":[
              {"label":"验证重复元素","prompt":"用 [3,3] 验证先查询后插入的过程？"},
              {"label":"分析正确性","prompt":"为什么哈希表只保存之前的元素就能保证下标不同？"},
              {"label":"检查复杂度","prompt":"这个方案的时间与空间复杂度分别是多少？"}]}
            """;

    public static void main(String[] args) throws Exception {
        StubModel model = new StubModel();
        ChatSuggestionService service = new ChatSuggestionService();
        inject(service, "chatSuggestionModel", model);
        inject(service, "objectMapper", MAPPER);
        ChatSuggestionController controller = new ChatSuggestionController();
        inject(controller, "chatSuggestionService", service);
        MockMvc mvc = new TestMvc(controller).build();

        var result = mvc.perform(post("/api/v1/chat/suggestions")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andReturn().getResponse();
        JsonNode json = MAPPER.readTree(result.getContentAsByteArray());
        check(result.getStatus() == 200 && "0000".equals(json.path("code").asText()), "HTTP 成功");
        check(json.path("data").size() == 3, "返回三个建议");
        check("验证重复元素".equals(json.path("data").get(0).path("label").asText()), "中文标签映射");
        check(json.path("data").get(0).path("prompt").asText().contains("[3,3]"), "完整问题映射");
        check("demo-agent".equals(model.agentId), "Agent 参数透传");
        JsonNode context = MAPPER.readTree(model.context);
        check(context.path("messages").size() == 2 && !context.has("userId") && !context.has("sessionId"), "过滤及身份隔离");
        System.out.println("HTTP suggestion response: " + json);

        JsonNode invalid = call(mvc, "{}");
        check("0002".equals(invalid.path("code").asText()), "缺少参数安全返回");
        model.output = "not-json";
        check("0001".equals(call(mvc, REQUEST).path("code").asText()), "模型错误 JSON 包装");
        model.fail = true;
        JsonNode failed = call(mvc, REQUEST);
        check("0001".equals(failed.path("code").asText()) && !failed.toString().contains("private-detail"), "超时安全包装");
        check(mvc.perform(get("/api/v1/chat/suggestions")).andReturn().getResponse().getStatus() == 405, "仅支持 POST");
        check(mvc.perform(post("/api/v1/chat/suggestions").contentType(MediaType.APPLICATION_JSON)
                .content("{bad")).andReturn().getResponse().getStatus() == 400, "非法请求 JSON 返回 400");
        var cors = mvc.perform(options("/api/v1/chat/suggestions")
                .header("Origin", "chrome-extension://test")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")).andReturn().getResponse();
        check(cors.getStatus() == 200 && "*".equals(cors.getHeader("Access-Control-Allow-Origin")), "插件跨域预检");
        System.out.println("ChatSuggestionControllerSmokeTest PASS");
    }

    private static JsonNode call(MockMvc mvc, String request) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/v1/chat/suggestions")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getContentAsByteArray());
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static class StubModel implements ChatSuggestionModel {
        String output = MODEL_RESPONSE;
        String context;
        String agentId;
        boolean fail;

        @Override
        public String generate(String agentId, String instruction, String contextJson) {
            this.agentId = agentId;
            this.context = contextJson;
            if (fail) throw new IllegalStateException("private-detail", new java.util.concurrent.TimeoutException());
            return output;
        }
    }

    /** Spring 6.2 standalone 模式需显式补充 CORS 预检使用的 HandlerAdapter。 */
    private static class TestMvc extends StandaloneMockMvcBuilder {
        TestMvc(Object controller) { super(controller); }

        @Override
        protected java.util.Map<String, Object> extendMvcSingletons(jakarta.servlet.ServletContext context) {
            return java.util.Map.of("httpRequestHandlerAdapter", new HttpRequestHandlerAdapter());
        }
    }
}
