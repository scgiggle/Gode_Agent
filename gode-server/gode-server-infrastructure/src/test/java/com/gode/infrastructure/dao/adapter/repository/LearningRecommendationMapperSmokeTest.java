package com.gode.infrastructure.dao.adapter.repository;

import com.gode.infrastructure.dao.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** 真实 MyBatis 动态 SQL 解析和仓储参数验证，不读取真实数据库。 */
public class LearningRecommendationMapperSmokeTest {
    static int checks;
    public static void main(String[] args)throws Exception{
        Configuration config=new Configuration();
        for(String name:List.of("leetcode_mistake.xml","leetcode_mistake_review.xml")){
            try(InputStream in=Files.newInputStream(Path.of("gode-server-app/src/main/resources/mybatis/mapper",name))){
                new XMLMapperBuilder(in,config,name,config.getSqlFragments()).parse();
            }
        }
        Map<String,Object> params=new HashMap<>(Map.of("userId","u","limit",100,"problemSlugs",List.of("two-sum","quoted'"),"mistakeIds",List.of(1L,2L)));
        String recent=sql(config,"ILeetcodeMistakeDao.queryRecentLearningMistakes",params);
        check(recent.contains("WHERE user_id = ?")&&recent.endsWith("ORDER BY updated_at DESC, id DESC LIMIT ?"),"最近样本按用户限定且有数量上限");
        String exact=sql(config,"ILeetcodeMistakeDao.queryByUserAndProblemSlugs",params);
        check(exact.contains("WHERE user_id = ?")&&exact.contains("problem_slug IN ( ? , ? )"),"候选状态批量查询限定用户");
        check(!exact.contains("quoted'"),"slug参数绑定，不拼接SQL");
        String reviews=sql(config,"ILeetcodeMistakeReviewDao.queryLearningReviews",params);
        check(reviews.contains("INNER JOIN leetcode_mistake AS mistake ON mistake.id = review.mistake_id")&&reviews.contains("WHERE mistake.user_id = ?"),"复盘通过主表JOIN归属过滤");
        check(reviews.contains("mistake.id IN ( ? , ? )")&&reviews.endsWith("ORDER BY review.created_at DESC, review.id DESC LIMIT ?"),"只读取指定样本近期复盘且限制数量");
        params.put("mistakeIds",List.of());params.put("problemSlugs",List.of());
        check(sql(config,"ILeetcodeMistakeDao.queryByUserAndProblemSlugs",params).contains("AND 1 = 0"),"空slug集合不读取所有题目");
        check(sql(config,"ILeetcodeMistakeReviewDao.queryLearningReviews",params).contains("AND 1 = 0"),"空ID集合不读取所有复盘");

        List<Object[]> captured=new ArrayList<>();
        ILeetcodeMistakeDao dao=(ILeetcodeMistakeDao)Proxy.newProxyInstance(ILeetcodeMistakeDao.class.getClassLoader(),new Class[]{ILeetcodeMistakeDao.class},
                (p,m,v)->{captured.add(v);return List.of();});
        ILeetcodeMistakeReviewDao reviewDao=(ILeetcodeMistakeReviewDao)Proxy.newProxyInstance(ILeetcodeMistakeReviewDao.class.getClassLoader(),new Class[]{ILeetcodeMistakeReviewDao.class},
                (p,m,v)->{captured.add(v);return List.of();});
        LeetcodeMistakeRepository repository=new LeetcodeMistakeRepository();
        inject(repository,"leetcodeMistakeDao",dao);inject(repository,"leetcodeMistakeReviewDao",reviewDao);
        repository.queryRecentLearningMistakes("u",Integer.MAX_VALUE);
        check(captured.get(0)[1].equals(100),"仓储限制100题");
        repository.queryLearningReviews("u",List.of(1L),Integer.MAX_VALUE);
        check(captured.get(1)[2].equals(300),"仓储限制300条复盘");
        int calls=captured.size();
        repository.queryLearningReviews("u",List.of(),300);repository.queryByUserAndProblemSlugs("u",List.of());
        check(captured.size()==calls,"空集合在仓储处短路，不发无意义查询");
        System.out.println("LearningRecommendationMapperSmokeTest: "+checks+" checks passed");
    }
    static String sql(Configuration config,String name,Map<String,Object> params){return config.getMappedStatement("com.gode.infrastructure.dao."+name).getBoundSql(params).getSql().replaceAll("\\s+"," ").trim();}
    static void inject(Object target,String field,Object value)throws Exception{var f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
