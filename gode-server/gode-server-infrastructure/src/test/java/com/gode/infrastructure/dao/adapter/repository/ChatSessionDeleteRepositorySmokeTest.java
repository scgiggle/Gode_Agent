package com.gode.infrastructure.dao.adapter.repository;

import com.gode.domain.agent.adapter.repository.IChatHistoryRepository;
import com.gode.infrastructure.dao.*;
import com.gode.infrastructure.dao.po.ChatSessionPO;
import com.gode.types.exception.AppException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 真实 MyBatis 解析 + Spring 事务代理，数据库操作模拟；不删除任何用户数据。 */
public class ChatSessionDeleteRepositorySmokeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Configuration config=new Configuration();
        for(String name:List.of("chat_session_mapper.xml","chat_message_mapper.xml","chat_milestone_mapper.xml")){
            try(InputStream in=Files.newInputStream(Path.of("gode-server-app/src/main/resources/mybatis/mapper",name))){
                new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();
            }
        }
        Map<String,Object> params=new HashMap<>(Map.of("agentId","demo","userId","u","sessionId","a","offset",0,"limit",20,"beforeMessageId",10L));
        String ownership=sql(config,"com.gode.infrastructure.dao.IChatSessionDao.lockOwnedSession",params);
        check(ownership.contains("id = ? AND user_id = ? AND agent_id = ?")&&ownership.endsWith("FOR UPDATE"),"归属校验加行锁");
        String main=sql(config,"com.gode.infrastructure.dao.IChatSessionDao.deleteOwnedSession",params);
        check(main.contains("id = ? AND user_id = ? AND agent_id = ?"),"删除主表约束用户与Agent");
        String visible=sql(config,"com.gode.infrastructure.dao.IChatMessageDao.queryVisibleMessagePage",params);
        check(visible.contains("role IN ('user', 'assistant')")&&visible.contains("id < ?")&&visible.contains("ORDER BY id DESC"),"消息分页与角色过滤");
        params.remove("beforeMessageId");
        check(!sql(config,"com.gode.infrastructure.dao.IChatMessageDao.queryVisibleMessagePage",params).contains("id <"),"无cursor读取最新页");
        check(sql(config,"com.gode.infrastructure.dao.IChatSessionDao.querySessionPage",params).contains("ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?"),"列表排序和分页");
        check(sql(config,"com.gode.infrastructure.dao.IChatMessageDao.deleteBySessionId",params).equals("DELETE FROM chat_message WHERE session_id = ?"),"消息只按目标会话删除");
        check(sql(config,"com.gode.infrastructure.dao.IChatMilestoneDao.deleteBySessionId",params).equals("DELETE FROM chat_milestone WHERE session_id = ?"),"里程碑只按目标会话删除");

        State state=new State(); List<String> calls=new ArrayList<>();
        IChatSessionDao sessions=proxy(IChatSessionDao.class,(method,values)->{
            calls.add(method);
            if(method.equals("lockOwnedSession")){
                return state.session&&"demo".equals(values[0])&&"u".equals(values[1])&&"a".equals(values[2])
                    ? ChatSessionPO.builder().id("a").agentId("demo").userId("u").build():null;
            }
            if(method.equals("deleteOwnedSession")){
                if(state.mainMiss)return 0;
                state.session=false;return 1;
            }
            throw new UnsupportedOperationException(method);
        });
        IChatMessageDao messages=proxy(IChatMessageDao.class,(method,values)->{
            calls.add(method); int count=state.messages;state.messages=0;return count;
        });
        IChatMilestoneDao milestones=proxy(IChatMilestoneDao.class,(method,values)->{
            calls.add(method);
            if(state.failMilestone)throw new IllegalStateException("simulated DB exception");
            int count=state.milestones;state.milestones=0;return count;
        });
        ChatHistoryRepository target=new ChatHistoryRepository();
        inject(target,"chatSessionDao",sessions);inject(target,"chatMessageDao",messages);inject(target,"chatMilestoneDao",milestones);
        Tx tx=new Tx(state);
        ProxyFactory factory=new ProxyFactory(target);
        factory.setInterfaces(IChatHistoryRepository.class);
        factory.addAdvice(new TransactionInterceptor(tx,new AnnotationTransactionAttributeSource()));
        IChatHistoryRepository repo=(IChatHistoryRepository)factory.getProxy();

        repo.deleteOwnedSession("demo","u","a");
        check(!state.session&&state.messages==0&&state.milestones==0,"成功级联删除");
        check(tx.commits==1&&tx.rollbacks==0,"成功提交事务");
        check(calls.equals(List.of("lockOwnedSession","deleteBySessionId","deleteBySessionId","deleteOwnedSession")),"先校验，先子后主");

        state.reset();state.mainMiss=true;calls.clear();
        expectFailure(()->repo.deleteOwnedSession("demo","u","a"));
        check(state.session&&state.messages==2&&state.milestones==1,"主表0行时回滚全部子记录");
        check(tx.rollbacks==1,"业务异常触发真实Spring事务代理回滚");

        state.reset();state.failMilestone=true;calls.clear();
        expectFailure(()->repo.deleteOwnedSession("demo","u","a"));
        check(state.session&&state.messages==2&&state.milestones==1,"中途数据库异常回滚已删消息");
        check(tx.rollbacks==2,"运行异常也回滚");

        state.reset();calls.clear();
        expectFailure(()->repo.deleteOwnedSession("demo","other","a"));
        check(calls.equals(List.of("lockOwnedSession"))&&state.messages==2,"越权在任何子记录删除前拒绝");
        check(state.longTermMemories==7&&state.mistakes==3,"长期记忆与错题不在删除事务内");
        System.out.println("ChatSessionDeleteRepositorySmokeTest: "+checks+" checks passed");
    }
    interface Handler{Object call(String method,Object[] args);}
    @SuppressWarnings("unchecked")
    static <T>T proxy(Class<T> type,Handler h){
        return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->h.call(m.getName(),a));
    }
    static class State{
        boolean session=true,mainMiss,failMilestone;
        int messages=2,milestones=1,longTermMemories=7,mistakes=3;
        void reset(){session=true;messages=2;milestones=1;mainMiss=false;failMilestone=false;}
    }
    static class Tx extends AbstractPlatformTransactionManager{
        final State state; boolean session; int messages,milestones,commits,rollbacks;
        Tx(State state){this.state=state;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition def){session=state.session;messages=state.messages;milestones=state.milestones;}
        protected void doCommit(DefaultTransactionStatus status){commits++;}
        protected void doRollback(DefaultTransactionStatus status){rollbacks++;state.session=session;state.messages=messages;state.milestones=milestones;}
    }
    static String sql(Configuration c,String name,Map<String,Object> params){return c.getMappedStatement(name).getBoundSql(params).getSql().replaceAll("\\s+"," ").trim();}
    static void inject(Object target,String field,Object value)throws Exception{var f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    static void expectFailure(Runnable action){try{action.run();throw new AssertionError("应失败");}catch(AppException|IllegalStateException expected){}}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
