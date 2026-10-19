package com.gode.domain.agent.service.chat.session;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.domain.agent.model.entity.*;
import com.gode.domain.agent.model.valobj.AiAgentRegisterVO;
import com.gode.domain.agent.model.valobj.intent.*;
import com.gode.domain.agent.service.*;
import com.gode.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.gode.domain.agent.service.armory.matter.session.CustomAdkSessionService;
import com.gode.domain.agent.service.context.ChatContextService;
import com.gode.domain.agent.service.context.provider.impl.*;
import com.gode.domain.agent.service.intent.ContextTracker;
import com.gode.domain.agent.service.intent.IntentService;
import com.gode.domain.agent.service.prompt.PromptService;
import com.gode.domain.agent.service.prompt.dynamic.MilestoneTracker;
import com.gode.types.exception.AppException;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.model.ChatModel;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 无网络、无数据库：验证会话生命周期、归属、冷启动及只有长期记忆能跨会话。 */
public class ChatSessionServiceSmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Map<String, ChatSessionEntity> rows = new LinkedHashMap<>();
        Map<String, List<ChatMessageEntity>> messages = new HashMap<>();
        Map<String, List<com.gode.domain.agent.model.valobj.prompt.MilestoneVO>> milestones = new HashMap<>();
        boolean[] failWrite = {false}, failDelete = {false};
        IChatHistoryRepository repo = (IChatHistoryRepository) Proxy.newProxyInstance(
                IChatHistoryRepository.class.getClassLoader(), new Class[]{IChatHistoryRepository.class}, (p, method, values) -> {
            String id;
            switch (method.getName()) {
                case "saveSession":
                    if (failWrite[0]) throw new IllegalStateException("simulated DB failure");
                    ChatSessionEntity s = (ChatSessionEntity) values[0]; s.setCreatedAt(LocalDateTime.now()); rows.put(s.getId(), s); return null;
                case "queryOwnedSession":
                    ChatSessionEntity found = rows.get(values[2]);
                    return found != null && found.getAgentId().equals(values[0]) && found.getUserId().equals(values[1]) ? found : null;
                case "querySessionPage":
                    List<ChatSessionEntity> all = rows.values().stream().filter(s2 -> s2.getAgentId().equals(values[0]) && s2.getUserId().equals(values[1])).toList();
                    int from = Math.min((Integer) values[2], all.size());
                    return all.subList(from, Math.min(from + (Integer) values[3], all.size()));
                case "queryVisibleMessagePage":
                    return messages.getOrDefault(values[0], List.of()).stream()
                            .filter(m -> List.of("user", "assistant").contains(m.getRole()))
                            .filter(m -> values[1] == null || m.getId() < (Long) values[1])
                            .sorted(Comparator.comparing(ChatMessageEntity::getId).reversed()).limit((Integer) values[2]).toList();
                case "getRecentMessages": return messages.getOrDefault(values[0], List.of());
                case "updateDefaultSessionTitle":
                    ChatSessionEntity current = rows.get(values[0]);
                    if ("新对话".equals(current.getTitle())) current.setTitle((String) values[1]); return null;
                case "deleteOwnedSession":
                    if (failDelete[0]) throw new IllegalStateException("simulated transaction failure");
                    id = (String) values[2]; rows.remove(id); messages.remove(id); milestones.remove(id); return null;
                case "getRecentMilestones": return milestones.getOrDefault(values[0], List.of());
                case "saveMilestone":
                    milestones.computeIfAbsent((String) values[0], k -> new ArrayList<>()).add((com.gode.domain.agent.model.valobj.prompt.MilestoneVO) values[1]); return null;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = (ChatModel) Proxy.newProxyInstance(ChatModel.class.getClassLoader(), new Class[]{ChatModel.class},
                (p, method, values) -> { modelCalls.incrementAndGet(); throw new AssertionError("不得调用真实模型"); });
        CustomAdkSessionService adk = new CustomAdkSessionService();
        Runner runner = Runner.builder().agent(LlmAgent.builder().name("assistant").model(new SpringAI(model)).build())
                .appName("test-app").sessionService(adk).build();
        AiAgentRegisterVO register = AiAgentRegisterVO.builder().agentId("demo").appName("test-app").runner(runner).build();
        DefaultArmoryFactory factory = new DefaultArmoryFactory() {
            @Override public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) { return "demo".equals(agentId) ? register : null; }
        };
        ChatSessionActivity activity = new ChatSessionActivity();
        ContextTracker tracker = new ContextTracker();
        IntentService intent = new IntentService(); inject(intent, "contextTracker", tracker);
        ToolResultProvider tools = new ToolResultProvider();
        TaskProvider tasks = new TaskProvider(); inject(tasks, "intentService", intent);
        LongTermMemoryProvider longTerm = new LongTermMemoryProvider();
        ILongTermMemoryService memory = (ILongTermMemoryService) Proxy.newProxyInstance(ILongTermMemoryService.class.getClassLoader(),
                new Class[]{ILongTermMemoryService.class}, (p, method, values) -> {
                    if (method.getName().equals("buildMemorySummary")) return "用户" + values[0] + "默认使用Java";
                    throw new AssertionError("会话生命周期不应清除或更改长期记忆");
                });
        inject(longTerm, "longTermMemoryService", memory);
        ChatContextService context = new ChatContextService(new ArrayList<>(List.of(tasks, tools, longTerm)));
        inject(context, "toolResultProvider", tools);
        MilestoneTracker milestone = new MilestoneTracker(); inject(milestone, "chatHistoryRepository", repo);
        PromptService prompt = new PromptService(); inject(prompt, "milestoneTracker", milestone);
        ChatSessionService service = new ChatSessionService();
        inject(service,"defaultArmoryFactory",factory); inject(service,"chatHistoryRepository",repo);
        inject(service,"activity",activity); inject(service,"customAdkSessionService",adk);
        inject(service,"chatContextService",context); inject(service,"promptService",prompt); inject(service,"intentService",intent);

        String a = service.createSession("demo","u","新对话"), b = service.createSession("demo","u","另一个聊天");
        check(!a.equals(b) && rows.size()==2,"每次创建独立Session并落库");
        check(rows.get(a).getMessageCount()==0,"初始消息计数");
        check(service.list("demo","u",0,1).isHasMore(),"列表分页");
        check(service.list("demo","other",0,20).getSessions().isEmpty(),"列表用户隔离");
        check(service.list("different","u",0,20).getSessions().isEmpty(),"列表Agent隔离");
        expectFailure(()->service.detail("demo","other",a,null,50),"消息越权");
        expectFailure(()->service.deleteSession("demo","other",a),"删除越权");
        check(rows.size()==2,"越权没有删除记录");
        expectFailure(()->service.list("demo","u",-1,20),"负offset");
        expectFailure(()->service.detail("demo","u",a,null,101),"limit上限");

        messages.put(a,List.of(msg(1L,a,"user","问题A"),msg(2L,a,"assistant","回答A"),msg(3L,a,"tool","不展示的工具内容")));
        var detail = service.detail("demo","u",a,null,1);
        check(detail.isHasMore() && detail.getMessages().get(0).getId()==2L,"历史只展示普通消息，最近向前分页");
        check(service.detail("demo","u",a,2L,50).getMessages().get(0).getContent().equals("问题A"),"向前cursor");
        messages.put(b,List.of(msg(4L,b,"user","问题B"),msg(5L,b,"assistant","回答B")));

        try(var lease=service.beginMessage("demo","u",a,"当前力扣题目：两数之和\n用户问题：给我提示")){
            check(rows.get(a).getTitle().equals("给我提示"),"默认标题取真实用户问题");
            expectFailure(()->service.beginMessage("demo","u",a,"重复发送"),"同Session禁止并发");
            expectFailure(()->service.deleteSession("demo","u",a),"运行中不能删除");
            try(var otherLease=service.beginMessage("demo","u",b,"独立问题")){check(true,"另一Session可独立运行");}
        }
        try(var ignored=activity.acquire(a)){check(true,"结束后释放租约");}
        expectFailure(()->service.beginMessage("demo","other",a,"越权"),"续聊校验归属");
        try(var ignored=activity.acquire(a)){check(true,"校验失败也释放租约");}

        tracker.setTaskState(a,TaskStateVO.builder().taskDescription("A的任务").rootIntent(IntentTypeEnumVO.HINT).build());
        tools.pushResult(a,"similar","A的工具结果");
        milestone.detectAndRecord(a,"user","不要直接给答案");
        var ctxA=context.buildPromptContext(a,"u",List.of(Map.of("role","user","content","A历史")));
        var ctxB=context.buildPromptContext(b,"u",List.of(Map.of("role","user","content","B历史")));
        check(ctxA.getTaskDescription().equals("A的任务") && ctxB.getTaskDescription().equals("B历史"),"当前任务按Session计算");
        check(ctxA.getToolResultSummary().contains("A的工具结果") && ctxB.getToolResultSummary()==null,"工具摘要隔离");
        check(ctxA.getLongTermMemorySummary().equals(ctxB.getLongTermMemorySummary()),"只有用户长期记忆可跨Session");
        check(tasks.provide(b,"u",List.of(Map.of("role","user","content","旧题"),Map.of("role","user","content","新题")))
                .get("taskDescription").equals("新题"),"同Session切题时任务回退到最新用户消息");

        var sessionA=adk.getSession("test-app","u",a,Optional.empty()).blockingGet();
        adk.appendEvent(sessionA, Event.builder().author("assistant").invocationId("test")
                .actions(EventActions.builder().stateDelta(Map.of("task","A","user:task","只属于A")).build())
                .content(Content.fromParts(Part.fromText("A内容"))).build()).blockingGet();
        var sessionB=adk.getSession("test-app","u",b,Optional.empty()).blockingGet();
        check(sessionB.state().get("task")==null && sessionB.state().get("user:task")==null,"ADK State不跨Session共享");
        adk.removeUserSession("u",a);
        try(var ignored=service.beginMessage("demo","u",a,"继续")){}
        var restored=adk.getSession("test-app","u",a,Optional.empty()).blockingGet();
        String restoredText=restored.events().stream().map(Event::stringifyContent).reduce("",String::concat);
        check(restoredText.contains("问题A") && restoredText.contains("回答A") && !restoredText.contains("问题B"),"重启只恢复该Session历史文本");
        check(restored.state().get("task")==null,"重启不继承其他会话状态");

        failDelete[0]=true;
        try{service.deleteSession("demo","u",a);throw new AssertionError("应失败");}catch(IllegalStateException expected){}
        check(rows.containsKey(a) && !tools.provide(a,"u",List.of()).isEmpty(),"删除失败保留数据库和内存上下文");
        failDelete[0]=false;
        service.deleteSession("demo","u",a);
        check(!rows.containsKey(a)&&rows.containsKey(b)&&!messages.containsKey(a),"删除主会话及消息，不影响其他Session");
        check(adk.getSession("test-app","u",a,Optional.empty()).blockingGet()==null,"删除ADK快照");
        check(tools.provide(a,"u",List.of()).isEmpty(),"清理工具结果缓存");
        check(tracker.getTaskState(a)==null && milestone.getRecent(a,50).isEmpty(),"清理任务态和里程碑");
        expectFailure(()->service.beginMessage("demo","u",a,"删除后续聊"),"删除ID不能自动复活");
        failWrite[0]=true;
        int before=adk.listSessions("test-app","u").blockingGet().sessions().size();
        try{service.createSession("demo","u",null);throw new AssertionError("应失败");}catch(IllegalStateException expected){}
        check(before==adk.listSessions("test-app","u").blockingGet().sessions().size(),"创建落库失败清理ADK临时会话");
        check(modelCalls.get()==0,"所有检查不调用模型");
        runner.close().blockingAwait();
        System.out.println("ChatSessionServiceSmokeTest: "+checks+" checks passed");
    }
    private static ChatMessageEntity msg(Long id,String session,String role,String text){
        return ChatMessageEntity.builder().id(id).sessionId(session).role(role).content(text).build();
    }
    private static void inject(Object target,String field,Object value)throws Exception{
        var f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);
    }
    private static void expectFailure(Runnable action,String label){
        try{action.run();throw new AssertionError(label);}catch(AppException expected){check(true,label);}
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
