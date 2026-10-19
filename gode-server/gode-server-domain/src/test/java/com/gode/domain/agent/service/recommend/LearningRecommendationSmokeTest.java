package com.gode.domain.agent.service.recommend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.*;
import com.gode.domain.agent.model.valobj.*;
import com.gode.domain.agent.model.valobj.dynamic.*;
import com.gode.domain.agent.model.valobj.recommend.*;
import com.gode.domain.agent.service.ISimilarProblemService;
import com.gode.domain.agent.service.armory.catalog.AgentCatalog;
import com.gode.domain.agent.service.armory.matter.session.factory.CustomRunnerFactory;
import com.gode.domain.agent.service.armory.matter.tools.*;
import com.google.adk.agents.*;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.*;
import com.google.adk.tools.*;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

/** 使用真实 ADK FunctionTool 和无模型子 Agent，不连接数据库、力扣或模型。 */
public class LearningRecommendationSmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        f.candidates.addAll(List.of(problem("array-first", "简单", "array"), problem("hard-hash", "困难", "hash-table"),
                problem("easy-hash", "简单", "Hash Table"), problem("known-mastered", "简单", "hash-table")));
        f.recent.addAll(List.of(mistake(1, "u", "two-sum", "待复习", "哈希表,数组"),
                mistake(2, "u", "another-hash", "复习中", "Hash Table"),
                mistake(3, "u", "mastered-dp", "已掌握", "动态规划"),
                mistake(4, "other", "foreign-graph", "待复习", "图")));
        f.known.add(mistake(99, "u", "known-mastered", "已掌握", "哈希表"));
        f.reviews.addAll(List.of(review(1,1,"先插入造成自匹配和下标复用，不是超时。"),
                review(2,1,"重复元素边界处理错误，仍然先插入。"), review(3,2,"重复使用同一元素。"),
                review(4,3,"状态转移错误"), review(5,4,"超时")));
        var result = f.service().recommend("u","two-sum",3);
        check(result.personalized(),"学习记录参与个性化");
        check(result.recommendations().get(0).getTitleSlug().equals("easy-hash"),"薄弱知识点优先，相同知识点先同难度");
        check(result.recommendations().get(1).getTitleSlug().equals("hard-hash"),"难度不能压过具体薄弱点匹配");
        check(result.excludedMasteredCount()==1,"已掌握候选即使不在近期样本中也排除");
        check(result.sampledProblemCount()==3&&result.sampledReviewCount()==4,"外部用户记录和复盘不会进入样本");
        var hash=result.weakTopics().stream().filter(t->t.topic().equals("哈希表")).findFirst().orElseThrow();
        check(hash.problemCount()==2&&hash.reviewCount()==3,"同题多次复盘不当作多道错题，中英文标签统一");
        check(result.weakTopics().stream().noneMatch(t->t.topic().equals("动态规划")||t.topic().equals("图")),"已掌握和其他用户不是当前薄弱项");
        check(result.errorPatterns().stream().noneMatch(e->e.name().equals("复杂度与性能")||e.name().equals("状态与转移")),"否定错因、已掌握错因及改进方案不误计");
        check(result.recommendations().get(0).getPracticeFocus().contains("下标与元素复用"),"匹配题附建议检查点");
        check(result.recommendations().get(2).getRecommendationReason().contains("数组"),"推荐说明含实际证据");
        check(f.lastIds.equals(List.of(1L,2L,3L)),"批量复盘查询仅传本人的错题ID");
        check(f.lastSlugs.contains("known-mastered")&&f.lastSlugs.contains("two-sum"),"候选及当前题状态精确查询");
        check(f.queryUsers.stream().allMatch("u"::equals),"所有查询限定实际用户");
        check(f.lastCandidateLimit==20,"先扩大候选池再截取，而非只排序三道题");
        check(f.candidates.get(0).getRecommendationReason()==null,"不改写原始候选或共享数据");

        var other=f.service().recommend("other","two-sum",3);
        check(other.recommendations().stream().anyMatch(p->p.getTitleSlug().equals("known-mastered"))==false,"数量上限仍受请求限制");
        var otherAll=f.service().recommend("other","two-sum",5);
        check(otherAll.excludedMasteredCount()==0&&otherAll.recommendations().size()==4,"另一个用户不会继承u的掌握状态");
        check(otherAll.weakTopics().size()==1&&otherAll.weakTopics().get(0).topic().equals("图"),"不同用户画像隔离");

        f.known.get(0).setStatus("待复习");
        var changed=f.service().recommend("u","two-sum",5);
        check(changed.recommendations().stream().anyMatch(p->p.getTitleSlug().equals("known-mastered")),"状态改变立即生效，没有跨用户缓存");
        f.known.get(0).setStatus("已掌握");
        f.failReviews=true;
        var fallback=f.service().recommend("u","two-sum",3);
        check(fallback.learningDataStatus().equals("unavailable")&&!fallback.personalized(),"DB中途失败不使用半成品画像");
        check(fallback.recommendations().get(0).getTitleSlug().equals("array-first")&&fallback.weakTopics().isEmpty(),"降级保留相似顺序，无虚构画像");
        f.failReviews=false;
        int calls=f.queryUsers.size();
        var anonymous=f.service().recommend("subagent-user","two-sum",3);
        check(anonymous.learningDataStatus().equals("missing_user")&&f.queryUsers.size()==calls,"保留身份缺失降级，不查询共用伪用户");

        Fixture empty=new Fixture();empty.candidates.addAll(f.candidates);
        var noHistory=empty.service().recommend("u","two-sum",1);
        check(noHistory.learningDataStatus().equals("no_history")&&!noHistory.personalized()&&noHistory.recommendations().size()==1,"无数据普通推荐");
        Fixture allMastered=new Fixture();allMastered.candidates.add(problem("old","简单","array"));
        allMastered.known.add(mistake(10,"u","old","已掌握","数组"));
        var none=allMastered.service().recommend("u","two-sum",3);
        check(none.recommendations().isEmpty()&&none.message().contains("均已掌握"),"全掌握时不强行补齐数量");
        Fixture missingTags=new Fixture();missingTags.candidates.add(problem("no-tags","简单"));missingTags.recent.add(f.recent.get(0));
        var missing=missingTags.service().recommend("u","two-sum",3);
        check(!missing.personalized()&&missing.recommendations().get(0).getMatchedWeakTopics().isEmpty(),"候选缺标签时不复制当前题标签");
        Fixture duplicates=new Fixture();duplicates.candidates.addAll(List.of(problem("two-sum","简单","array"),
                problem("one","简单","array"),problem("one","简单","array"),problem("../evil","简单","array")));
        check(duplicates.service().recommend("u","two-sum",3).recommendations().size()==1,"排除当前题、重复与非法slug");

        toolChecks(f);
        dispatchChecks();
        graphqlChecks();
        JsonNode configuration=new ObjectMapper(new YAMLFactory()).readTree(Path.of("gode-server-app/src/main/resources/application-dev.yml").toFile());
        JsonNode configuredAgent=configuration.findParents("name").stream().filter(n->n.path("name").asText().equals("toolRecommend")).findFirst().orElseThrow();
        check(configuredAgent.path("instruction").asText().contains("疑似薄弱点")&&configuredAgent.path("instruction").asText().contains("recommendationReason"),"YAML可解析且推荐Agent接入新证据提示词");
        System.out.println("LearningRecommendationSmokeTest: "+checks+" checks passed");
    }

    static void toolChecks(Fixture f)throws Exception {
        SimilarProblemTool bean=new SimilarProblemTool();
        var field=bean.getClass().getDeclaredField("learningRecommendationService");field.setAccessible(true);field.set(bean,f.service());
        FunctionTool tool=FunctionTool.create(bean,"recommendSimilarProblems");
        String declaration=tool.declaration().orElseThrow().toJson();
        check(declaration.contains("currentProblemSlug")&&declaration.contains("count")&&!declaration.contains("toolContext")&&!declaration.contains("userId"),"模型只能提供题目和数量，执行上下文不暴露");
        Map<String,Object> output=tool.runAsync(Map.of("currentProblemSlug","two-sum","count",3,"userId","other"),context("u")).blockingGet();
        check(Boolean.TRUE.equals(output.get("success"))&&Boolean.TRUE.equals(output.get("personalized")),"真实FunctionTool能注入上下文并返回学习证据");
        check(f.queryUsers.get(f.queryUsers.size()-1).equals("u"),"模型自行增加userId不能改变归属");
        check(output.containsKey("weakTopics")&&output.containsKey("recommendations"),"保留旧结果字段并新增画像字段");
        check(Boolean.FALSE.equals(tool.runAsync(Map.of("currentProblemSlug","../evil","count",3),context("u")).blockingGet().get("success")),"非法slug工具拒绝");
        check(((Number)tool.runAsync(Map.of("currentProblemSlug","two-sum"),context("u")).blockingGet().get("count")).intValue()<=3,"count不传默认3");
        check(new ObjectMapper().writeValueAsString(output).contains("recommendationReason"),"结果可JSON序列化供Agent读取");
    }

    static ToolContext context(String user) {
        Session session=Session.builder(new SessionKey("app",user,"parent-session")).state(new HashMap<>()).events(new ArrayList<>()).build();
        BaseAgent parent=new BaseAgent("parent","test",List.of(),List.of(),List.of()) {
            protected Flowable<Event> runAsyncImpl(InvocationContext ctx){return Flowable.empty();}
            protected Flowable<Event> runLiveImpl(InvocationContext ctx){return Flowable.empty();}
        };
        return ToolContext.builder(InvocationContext.builder().session(session).agent(parent)
                .sessionService(new InMemorySessionService()).invocationId("invocation-test").build()).build();
    }
    static void dispatchChecks() {
        List<Session> captured=new ArrayList<>();
        BaseAgent agent=new BaseAgent("child","test",List.of(),List.of(),List.of()) {
            protected Flowable<Event> runAsyncImpl(InvocationContext ctx){
                captured.add(ctx.session());
                return Flowable.just(Event.builder().author(name()).invocationId(ctx.invocationId())
                        .content(Content.builder().role("model").parts(List.of(Part.fromText("ok"))).build()).build());
            }
            protected Flowable<Event> runLiveImpl(InvocationContext ctx){return Flowable.empty();}
        };
        CustomRunnerFactory factory=new CustomRunnerFactory(){
            public Runner create(BaseAgent child,String app,List<BasePlugin> plugins){
                return Runner.builder().agent(child).appName(app).sessionService(new InMemorySessionService()).build();
            }
        };
        var single=new SubAgentDispatchTool(agent,factory);
        single.runAsync(Map.of("request","练习"),context("u")).blockingGet();
        check(captured.get(0).userId().equals("u"),"单子Agent透传父用户");
        check(!captured.get(0).id().equals("parent-session"),"单子Agent仍用隔离会话");
        AgentCatalog catalog=new AgentCatalog();catalog.register("parent",Map.of("child",agent));
        var dispatch=new SubAgentDispatchService(catalog,factory,Runnable::run);
        DynamicTask task=DynamicTask.builder().agentName("child").request("练习").build();
        dispatch.execute(AgentExecutionContext.builder().agentId("parent").userId("u2").parentSessionId("parent-session").build(),task);
        check(task.getStatus()==TaskStatus.COMPLETED&&captured.get(1).userId().equals("u2"),"批量/动态派发透传实际用户");
        check(!captured.get(1).id().equals(captured.get(0).id()),"子任务会话互不串联");
    }

    static void graphqlChecks()throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        class Provider extends LeetCodeSimilarProblemService {
            final List<Map<String,Object>> requests=new ArrayList<>();boolean failMetadata,failMain;
            Provider(){super(mapper);}
            protected JsonNode postGraphql(Map<String,Object> body,String slug)throws Exception{
                requests.add(body);
                if(body.get("operationName").equals("questionData")){
                    if(failMain)throw new IllegalStateException("network");
                    List<Map<String,Object>> similar=new ArrayList<>();
                    for(int i=0;i<7;i++)similar.add(Map.of("titleSlug","p-"+i,"title","P"+i,"difficulty","Easy"));
                    similar.add(similar.get(0));similar.add(Map.of("titleSlug","../evil","title","evil"));
                    return mapper.valueToTree(Map.of("data",Map.of("question",Map.of("difficulty","Easy",
                            "similarQuestions",mapper.writeValueAsString(similar),"topicTags",List.of(Map.of("name","Hash Table","slug","hash-table"))))));
                }
                if(failMetadata)throw new IllegalStateException("metadata unavailable");
                Map<String,Object> data=new LinkedHashMap<>();
                Map<?,?> variables=(Map<?,?>)body.get("variables");
                for(int i=0;i<variables.size();i++)data.put("q"+i,Map.of("titleSlug",variables.get("slug"+i),"difficulty","Medium",
                        "topicTags",List.of(Map.of("name","Array","slug","array"))));
                return mapper.valueToTree(Map.of("data",data));
            }
        }
        Provider provider=new Provider();
        var pool=provider.candidates("two-sum",20);
        check(pool.candidates().size()==7,"扩大真实候选池并去重/过滤非法slug");
        check(provider.requests.size()==2,"候选元数据一次批量查询，没有N次网络请求");
        check(pool.currentTopicTags().equals(List.of("hash-table"))&&pool.candidates().get(0).getTopicTags().equals(List.of("array")),"当前题和候选题标签分别取真实数据");
        check(pool.candidates().get(0).getDifficulty().equals("中等")&&pool.candidates().get(0).getUrl().equals("https://leetcode.cn/problems/p-0/"),"真实难度及合法cn链接");
        check(provider.recommend("two-sum",99).size()==5,"旧接口最大5兼容");
        provider.failMetadata=true;
        var degraded=provider.candidates("two-sum",20);
        check(degraded.candidates().size()==7&&!degraded.metadataAvailable()&&degraded.candidates().get(0).getTopicTags().isEmpty(),"元数据失败保留相似题、不推测标签");
        provider.failMain=true;
        try{provider.candidates("two-sum",3);throw new AssertionError("主查询失败不应伪造成功");}catch(IllegalStateException expected){check(true,"主查询失败仍报错");}
    }

    static class Fixture {
        final List<SimilarProblemVO> candidates=new ArrayList<>();
        final List<LeetcodeMistakeEntity> recent=new ArrayList<>(),known=new ArrayList<>();
        final List<LeetcodeMistakeReviewEntity> reviews=new ArrayList<>();
        final List<String> queryUsers=new ArrayList<>();
        List<Long> lastIds;List<String> lastSlugs;boolean failReviews;int lastCandidateLimit;
        @SuppressWarnings("unchecked")
        LearningProblemRecommendationService service(){
            ISimilarProblemService source=new ISimilarProblemService(){
                public List<SimilarProblemVO> recommend(String slug,int count){return candidates.stream().limit(count).toList();}
                public SimilarProblemCandidatesVO candidates(String slug,int limit){lastCandidateLimit=limit;return new SimilarProblemCandidatesVO("简单",List.of("hash-table"),candidates,true);}
            };
            ILeetcodeMistakeRepository repo=(ILeetcodeMistakeRepository)Proxy.newProxyInstance(ILeetcodeMistakeRepository.class.getClassLoader(),
                    new Class[]{ILeetcodeMistakeRepository.class},(p,m,args)->{
                        queryUsers.add((String)args[0]);
                        return switch(m.getName()){
                            case "queryRecentLearningMistakes" -> recent; // 故意混入外部用户，验证服务防线
                            case "queryByUserAndProblemSlugs" -> {lastSlugs=(List<String>)args[1];yield known;}
                            case "queryLearningReviews" -> {lastIds=(List<Long>)args[1];if(failReviews)throw new IllegalStateException("DB failure");yield reviews;}
                            default -> throw new AssertionError("只允许读取，不允许修改学习记录："+m.getName());
                        };
                    });
            return new LearningProblemRecommendationService(source,repo);
        }
    }
    static SimilarProblemVO problem(String slug,String difficulty,String...tags){return SimilarProblemVO.builder().title(slug).titleSlug(slug)
            .url("https://leetcode.cn/problems/"+slug+"/").difficulty(difficulty).topicTags(List.of(tags)).build();}
    static LeetcodeMistakeEntity mistake(long id,String user,String slug,String status,String tags){return LeetcodeMistakeEntity.builder()
            .id(id).userId(user).problemSlug(slug).status(status).tags(tags).build();}
    static LeetcodeMistakeReviewEntity review(long id,long mistake,String reason){return LeetcodeMistakeReviewEntity.builder().id(id).mistakeId(mistake)
            .failReason(reason).actionPlan("避免超时，正确处理溢出和状态转移").build();}
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
}
