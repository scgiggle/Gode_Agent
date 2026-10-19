package com.gode.infrastructure.dao.adapter.repository;

import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.infrastructure.dao.ILeetcodeMistakeDao;
import com.gode.infrastructure.dao.ILeetcodeMistakeReviewDao;
import com.gode.types.exception.AppException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 验证仓储参数及实际 Mapper 绑定，不连接数据库、不执行 DELETE SQL。 */
public class LeetcodeMistakeDeleteRepositorySmokeTest {
    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration();
        for (String file : List.of("leetcode_mistake.xml", "leetcode_mistake_review.xml")) {
            Path path = Path.of("gode-server-app/src/main/resources/mybatis/mapper", file);
            try (var input = Files.newInputStream(path)) {
                new XMLMapperBuilder(input, configuration, path.toString(), configuration.getSqlFragments()).parse();
            }
        }
        String mainId = ILeetcodeMistakeDao.class.getName() + ".deleteByIdAndUserId";
        String reviewId = ILeetcodeMistakeReviewDao.class.getName() + ".deleteByMistakeIdAndUserId";
        BoundSql mainSql = configuration.getMappedStatement(mainId).getBoundSql(Map.of("id", 7L, "userId", "u1"));
        BoundSql reviewSql = configuration.getMappedStatement(reviewId).getBoundSql(Map.of("mistakeId", 7L, "userId", "u1"));
        check(configuration.getMappedStatement(mainId).getSqlCommandType() == SqlCommandType.DELETE
                && configuration.getMappedStatement(reviewId).getSqlCommandType() == SqlCommandType.DELETE,
                "两条语句均注册为 DELETE");
        check(mainSql.getSql().replaceAll("\\s+", " ").contains("WHERE id = ? AND user_id = ?"),
                "主表删除同时约束 ID 与用户");
        String reviewStatement = reviewSql.getSql().replaceAll("\\s+", " ");
        check(reviewStatement.contains("INNER JOIN leetcode_mistake")
                && reviewStatement.contains("mistake.id = review.mistake_id")
                && reviewStatement.contains("WHERE review.mistake_id = ? AND mistake.user_id = ?"),
                "复盘删除通过主表校验用户归属");
        check(mainSql.getParameterMappings().stream().map(p -> p.getProperty()).toList().equals(List.of("id", "userId"))
                && reviewSql.getParameterMappings().stream().map(p -> p.getProperty()).toList().equals(List.of("mistakeId", "userId")),
                "Mapper 参数名称及占位符绑定一致");

        List<String> calls = new ArrayList<>();
        int[] updateCount = {1};
        ILeetcodeMistakeDao mainDao = (ILeetcodeMistakeDao) Proxy.newProxyInstance(
                ILeetcodeMistakeDao.class.getClassLoader(), new Class<?>[]{ILeetcodeMistakeDao.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("deleteByIdAndUserId")) {
                        calls.add("main:" + Arrays.toString(values));
                        return 1;
                    }
                    if (method.getName().equals("update")) return updateCount[0];
                    throw new UnsupportedOperationException(method.getName());
                });
        ILeetcodeMistakeReviewDao reviewDao = (ILeetcodeMistakeReviewDao) Proxy.newProxyInstance(
                ILeetcodeMistakeReviewDao.class.getClassLoader(), new Class<?>[]{ILeetcodeMistakeReviewDao.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("deleteByMistakeIdAndUserId")) {
                        calls.add("reviews:" + Arrays.toString(values));
                        return 3;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        LeetcodeMistakeRepository repository = new LeetcodeMistakeRepository();
        inject(repository, "leetcodeMistakeDao", mainDao);
        inject(repository, "leetcodeMistakeReviewDao", reviewDao);
        check(repository.deleteReviewsByMistakeId(7L, "u1") == 3
                && repository.deleteMistakeByIdAndUserId(7L, "u1") == 1
                && calls.equals(List.of("reviews:[7, u1]", "main:[7, u1]")), "仓储传递用户参数并返回实际删除条数");
        LeetcodeMistakeEntity record = LeetcodeMistakeEntity.builder().id(7L).userId("u1").build();
        repository.updateMistake(record);
        updateCount[0] = 0;
        try {
            repository.updateMistake(record);
            throw new AssertionError("并发删除后更新 0 行应中断保存，不能继续插入复盘");
        } catch (AppException expected) {
            check(expected.getInfo().contains("已被删除"), "并发删除后保存返回明确错误");
        }
        System.out.println("Mistake delete repository and mapper smoke checks passed");
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
