package com.gode.test.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.api.dto.ChatSessionRequestDTO;
import com.gode.domain.agent.model.entity.*;
import com.gode.domain.agent.model.valobj.chat.*;
import com.gode.domain.agent.service.IChatSessionService;
import com.gode.trigger.http.ChatSessionController;
import com.gode.types.exception.AppException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 本地HTTP序列化及分页/业务错误验证，不连接数据库或模型。 */
public class ChatSessionControllerSmokeTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        AtomicInteger deletions=new AtomicInteger();
        ChatSessionEntity session=ChatSessionEntity.builder().id("a").agentId("demo").userId("u").title("独立聊天").messageCount(2).build();
        IChatSessionService service=(IChatSessionService)Proxy.newProxyInstance(IChatSessionService.class.getClassLoader(),
            new Class[]{IChatSessionService.class},(p,m,v)->{
                if(!"u".equals(v[1]))throw new AppException("0002","不属于当前用户");
                return switch(m.getName()){
                    case "list" -> new ChatSessionPageVO(List.of(session),true,20);
                    case "detail" -> new ChatSessionDetailVO(session,List.of(ChatMessageEntity.builder().id(2L).role("assistant").content("回答").build()),true,2L);
                    case "deleteSession" -> {deletions.incrementAndGet();yield null;}
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
        ChatSessionController controller=new ChatSessionController();
        var f=controller.getClass().getDeclaredField("chatSessionService");f.setAccessible(true);f.set(controller,service);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        ObjectMapper mapper=new ObjectMapper();
        var list=mvc.perform(get("/api/v1/chat/sessions").param("agentId","demo").param("userId","u")).andReturn().getResponse();
        var page=mapper.readTree(list.getContentAsByteArray());
        check(list.getStatus()==200&&page.path("code").asText().equals("0000"),"列表接口成功");
        check(page.path("data").path("sessions").get(0).path("sessionId").asText().equals("a"),"DTO输出Session ID");
        check(page.path("data").path("hasMore").asBoolean()&&page.path("data").path("nextOffset").asInt()==20,"列表分页字段");

        var detail=mvc.perform(get("/api/v1/chat/session_detail").param("agentId","demo").param("userId","u").param("sessionId","a")
                .param("beforeMessageId","3").param("limit","50")).andReturn().getResponse();
        var data=mapper.readTree(detail.getContentAsByteArray()).path("data");
        check(data.path("messages").get(0).path("id").asLong()==2&&data.path("beforeMessageId").asLong()==2,"历史消息与cursor输出");
        check(data.path("hasMore").asBoolean(),"消息分页字段");
        var wrong=mvc.perform(get("/api/v1/chat/session_detail").param("agentId","demo").param("userId","other").param("sessionId","a")).andReturn().getResponse();
        check(mapper.readTree(wrong.getContentAsByteArray()).path("code").asText().equals("0002"),"越权业务失败，不泄漏消息");
        var missing=mvc.perform(get("/api/v1/chat/sessions").param("agentId","demo")).andReturn().getResponse();
        check(missing.getStatus()==400,"缺少用户参数拒绝");
        var deleted=mvc.perform(post("/api/v1/chat/session_delete").contentType("application/json")
                .content("{\"agentId\":\"demo\",\"userId\":\"u\",\"sessionId\":\"a\"}")).andReturn().getResponse();
        check(mapper.readTree(deleted.getContentAsByteArray()).path("data").asBoolean()&&deletions.get()==1,"删除返回确认成功");
        mvc.perform(post("/api/v1/chat/session_delete").contentType("application/json")
                .content("{\"agentId\":\"demo\",\"userId\":\"other\",\"sessionId\":\"a\"}")).andReturn();
        check(deletions.get()==1,"越权删除不进入执行");
        System.out.println("ChatSessionControllerSmokeTest: "+checks+" checks passed");
    }
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
