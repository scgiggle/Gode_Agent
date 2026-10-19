package com.gode.domain.agent.service.mistake;

import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.service.ILeetcodeMistakeBookService;
import com.gode.types.exception.AppException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用真实 Spring 事务拦截器及内存事务资源验证删除回滚，不操作真实数据库。 */
public class LeetcodeMistakeDeleteTransactionSmokeTest {
    public static void main(String[] args) throws Exception {
        MemoryTransactions transactions = new MemoryTransactions();
        transactions.mistakes.put(1L, mistake(1L, "user-a"));
        transactions.mistakes.put(2L, mistake(2L, "user-b"));
        transactions.mistakes.put(3L, mistake(3L, "user-a")); // 没有复盘的旧主记录
        transactions.reviewOwners.put(11L, 1L);
        transactions.reviewOwners.put(12L, 1L);
        transactions.reviewOwners.put(21L, 2L);
        List<String> writes = new ArrayList<>();
        boolean[] failReviews = {false};
        boolean[] failMain = {false};
        ILeetcodeMistakeRepository repository = (ILeetcodeMistakeRepository) Proxy.newProxyInstance(
                ILeetcodeMistakeRepository.class.getClassLoader(), new Class<?>[]{ILeetcodeMistakeRepository.class},
                (proxy, method, values) -> {
                    Long id = (Long) values[0];
                    String userId = (String) values[1];
                    LeetcodeMistakeEntity record = transactions.mistakes.get(id);
                    boolean owned = record != null && record.getUserId().equals(userId);
                    switch (method.getName()) {
                        case "queryByIdAndUserId": return owned ? record : null;
                        case "deleteReviewsByMistakeId":
                            writes.add("reviews");
                            if (failReviews[0]) throw new IllegalStateException("simulated review delete failure");
                            int before = transactions.reviewOwners.size();
                            if (owned) transactions.reviewOwners.values().removeIf(id::equals);
                            return before - transactions.reviewOwners.size();
                        case "deleteMistakeByIdAndUserId":
                            writes.add("mistake");
                            if (!owned || failMain[0]) return 0;
                            transactions.mistakes.remove(id);
                            return 1;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        LeetcodeMistakeBookService target = new LeetcodeMistakeBookService();
        var field = LeetcodeMistakeBookService.class.getDeclaredField("mistakeRepository");
        field.setAccessible(true);
        field.set(target, repository);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ILeetcodeMistakeBookService service = (ILeetcodeMistakeBookService) factory.getProxy();

        expectFailure(() -> service.deleteMistake("user-b", 1L), AppException.class);
        expectFailure(() -> service.deleteMistake("user-a", 999L), AppException.class);
        expectFailure(() -> service.deleteMistake("", 1L), AppException.class);
        expectFailure(() -> service.deleteMistake("user-a", null), AppException.class);
        expectFailure(() -> service.deleteMistake("user-a", 0L), AppException.class);
        check(writes.isEmpty() && transactions.reviewOwners.size() == 3, "归属或参数错误不得写入");

        failMain[0] = true;
        int rollbacks = transactions.rollbacks;
        expectFailure(() -> service.deleteMistake("user-a", 1L), AppException.class);
        check(writes.equals(List.of("reviews", "mistake")), "先删复盘再删主记录");
        check(transactions.rollbacks == rollbacks + 1 && transactions.mistakes.size() == 3
                && transactions.reviewOwners.size() == 3, "主表影响 0 行时，复盘删除一起回滚");

        writes.clear();
        failMain[0] = false;
        failReviews[0] = true;
        expectFailure(() -> service.deleteMistake("user-a", 1L), IllegalStateException.class);
        check(writes.equals(List.of("reviews")) && transactions.mistakes.size() == 3
                && transactions.reviewOwners.size() == 3, "复盘删除失败，不删主记录");

        writes.clear();
        failReviews[0] = false;
        service.deleteMistake(" user-a ", 1L);
        check(writes.equals(List.of("reviews", "mistake")) && !transactions.mistakes.containsKey(1L)
                && transactions.reviewOwners.size() == 1 && transactions.reviewOwners.get(21L) == 2L,
                "成功删除整道错题，保留另一用户的同题及历史");
        check(transactions.commits == 1, "成功提交事务");
        writes.clear();
        expectFailure(() -> service.deleteMistake("user-a", 1L), AppException.class);
        check(writes.isEmpty(), "重复删除不影响其他记录");
        service.deleteMistake("user-a", 3L);
        check(transactions.mistakes.size() == 1 && transactions.reviewOwners.size() == 1,
                "没有复盘的主记录也能删除");
        System.out.println("Mistake delete transaction smoke checks passed");
    }

    private static LeetcodeMistakeEntity mistake(Long id, String userId) {
        return LeetcodeMistakeEntity.builder().id(id).userId(userId).problemId("1").build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action, Class<? extends RuntimeException> expected) {
        try { action.run(); throw new AssertionError("Expected " + expected.getSimpleName()); }
        catch (RuntimeException failure) { if (!expected.isInstance(failure)) throw failure; }
    }

    private static class MemoryTransactions extends AbstractPlatformTransactionManager {
        final Map<Long, LeetcodeMistakeEntity> mistakes = new LinkedHashMap<>();
        final Map<Long, Long> reviewOwners = new LinkedHashMap<>();
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() { return new Snapshot(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            Snapshot snapshot = (Snapshot) transaction;
            snapshot.mistakes = new LinkedHashMap<>(mistakes);
            snapshot.reviewOwners = new LinkedHashMap<>(reviewOwners);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            Snapshot snapshot = (Snapshot) status.getTransaction();
            mistakes.clear(); mistakes.putAll(snapshot.mistakes);
            reviewOwners.clear(); reviewOwners.putAll(snapshot.reviewOwners);
            rollbacks++;
        }
    }

    private static class Snapshot {
        Map<Long, LeetcodeMistakeEntity> mistakes;
        Map<Long, Long> reviewOwners;
    }
}
