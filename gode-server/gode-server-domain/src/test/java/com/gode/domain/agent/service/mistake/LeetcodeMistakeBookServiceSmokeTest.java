package com.gode.domain.agent.service.mistake;

import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.domain.agent.model.valobj.mistake.SaveMistakeCommand;
import com.gode.types.exception.AppException;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 无数据库的业务冒烟检查。运行 main；不替代 Spring 事务和 MySQL 集成测试。 */
public class LeetcodeMistakeBookServiceSmokeTest {
    public static void main(String[] args) throws Exception {
        List<LeetcodeMistakeEntity> mistakes = new ArrayList<>();
        List<LeetcodeMistakeReviewEntity> reviews = new ArrayList<>();
        int[] historyQueries = {0};
        boolean[] nullHistory = {false};
        ILeetcodeMistakeRepository repository = (ILeetcodeMistakeRepository) Proxy.newProxyInstance(
                ILeetcodeMistakeRepository.class.getClassLoader(),
                new Class<?>[]{ILeetcodeMistakeRepository.class}, (proxy, method, values) -> {
                    switch (method.getName()) {
                        case "queryByUserAndProblemId":
                            return mistakes.stream().filter(m -> m.getUserId().equals(values[0])
                                    && m.getProblemId().equals(values[1])).findFirst().orElse(null);
                        case "queryByIdAndUserId":
                            return mistakes.stream().filter(m -> m.getId().equals(values[0])
                                    && m.getUserId().equals(values[1])).findFirst().orElse(null);
                        case "insertMistake":
                            LeetcodeMistakeEntity mistake = (LeetcodeMistakeEntity) values[0];
                            mistake.setId((long) mistakes.size() + 1);
                            mistakes.add(mistake);
                            return null;
                        case "updateMistake": return null;
                        case "insertReview":
                            LeetcodeMistakeReviewEntity review = (LeetcodeMistakeReviewEntity) values[0];
                            review.setId((long) reviews.size() + 1);
                            reviews.add(review);
                            return null;
                        case "queryLatestReview":
                            throw new AssertionError("详情应从同一历史列表取最新复盘，不能单独查询");
                        case "queryReviewList":
                            historyQueries[0]++;
                            if (nullHistory[0]) return null;
                            return reviews.stream().filter(r -> r.getMistakeId().equals(values[0]))
                                    .sorted(Comparator.comparing(LeetcodeMistakeReviewEntity::getCreatedAt).reversed()
                                            .thenComparing(LeetcodeMistakeReviewEntity::getId, Comparator.reverseOrder()))
                                    .toList();
                        case "countPage":
                            return mistakes.stream().filter(m -> m.getUserId().equals(values[0])).count();
                        case "queryPage":
                            return mistakes.stream().filter(m -> m.getUserId().equals(values[0]))
                                    .skip((int) values[4]).limit((int) values[5]).toList();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        LeetcodeMistakeBookService service = new LeetcodeMistakeBookService();
        var field = LeetcodeMistakeBookService.class.getDeclaredField("mistakeRepository");
        field.setAccessible(true);
        field.set(service, repository);

        SaveMistakeCommand command = SaveMistakeCommand.builder().problemId("1")
                .problemSlug("two-sum").title("两数之和").difficulty("简单")
                .errorSummary("重复使用同一个元素").failReason("首次错因")
                .failCase("首次用例").aiAnalysis("先查询，再放入哈希表")
                .actionPlan("1. 检查元素下标").sourceSessionId("session-a").build();
        Long id = service.saveMistake("user-a", command);
        check(id != null && mistakes.size() == 1 && reviews.size() == 1, "保存主记录和复盘");
        command.setErrorSummary("最新主表摘要");
        command.setFailReason("第二次错因");
        command.setFailCase("第二次用例");
        command.setAiAnalysis("第二次分析");
        command.setActionPlan("1. 验证重复元素");
        command.setSourceSessionId("session-b");
        service.saveMistake("user-a", command);
        check(mistakes.size() == 1 && reviews.size() == 2, "重复错题追加历史，不重复创建主记录");
        // 模拟数据库时间相同的情况，仍按 ID 倒序确定最新复盘。
        LocalDateTime sameTime = LocalDateTime.of(2026, 10, 9, 12, 0);
        reviews.forEach(review -> review.setCreatedAt(sameTime));
        var detail = service.queryDetail("user-a", id);
        check(detail.getReviews().size() == 2 && detail.getReviews().get(0) == reviews.get(1)
                && detail.getLatestReview() == detail.getReviews().get(0), "完整倒序历史和最新复盘一致");
        check("首次错因".equals(detail.getReviews().get(1).getFailReason())
                && "首次用例".equals(detail.getReviews().get(1).getFailCase())
                && "先查询，再放入哈希表".equals(detail.getReviews().get(1).getAiAnalysis())
                && "1. 检查元素下标".equals(detail.getReviews().get(1).getActionPlan())
                && "session-a".equals(detail.getReviews().get(1).getSourceSessionId()), "旧复盘字段独立保留");
        check("最新主表摘要".equals(detail.getMistake().getErrorSummary())
                && detail.getMistake().getReviewCount() == 0, "主表摘要与复盘独立，记录数不累加学习次数");
        int queriesBefore = historyQueries[0];
        expectInvalid(() -> service.queryDetail("user-b", id));
        expectInvalid(() -> service.queryDetail("user-a", 999L));
        check(historyQueries[0] == queriesBefore, "归属校验失败不查询历史");
        nullHistory[0] = true;
        var missingHistory = service.queryDetail("user-a", id);
        check(missingHistory.getReviews().isEmpty() && missingHistory.getLatestReview() == null,
                "仓储返回 null 时，历史为空数组、最新复盘为空");
        nullHistory[0] = false;
        check(service.queryPage("user-a", null, null, null, null, null).getPageSize() == 10, "默认分页");
        check(service.queryPage("user-b", null, null, null, 1, 10).getRecords().isEmpty(), "用户隔离");
        check(service.queryPage("user-a", null, null, null, 2, 10).getRecords().isEmpty(), "超出末页");
        expectInvalid(() -> service.queryPage("user-a", null, null, null, 0, 10));
        expectInvalid(() -> service.queryPage("user-a", "bad", null, null, 1, 10));
        expectInvalid(() -> service.queryPage("user-a", null, null, null, Integer.MAX_VALUE, 100));
        service.startReview("user-a", id);
        check(mistakes.get(0).getReviewCount() == 0, "开始复习不增加次数");
        service.markMastered("user-a", id);
        service.markMastered("user-a", id);
        check(mistakes.get(0).getReviewCount() == 1 && mistakes.get(0).getNextReviewTime() == null,
                "重复标记掌握不累加，清空复习时间");
        service.saveMistake("user-a", command);
        check("待复习".equals(mistakes.get(0).getStatus()) && mistakes.get(0).getReviewCount() == 1,
                "再次出错重置状态，保留已完成次数");
        expectInvalid(() -> service.saveMistake("", command));
        expectInvalid(() -> service.saveMistake("user-a", null));
        // 允许旧数据中存在主记录但没有复盘的情况。
        mistakes.add(LeetcodeMistakeEntity.builder().id(2L).userId("user-a").problemId("2").build());
        var emptyDetail = service.queryDetail("user-a", 2L);
        check(emptyDetail.getReviews().isEmpty() && emptyDetail.getLatestReview() == null, "无历史的真实空列表");
        Long otherId = service.saveMistake("user-b", command);
        check(service.queryDetail("user-a", id).getReviews().size() == 3
                && service.queryDetail("user-b", otherId).getReviews().size() == 1, "不同用户同题历史隔离");
        LeetcodeMistakeDeleteTransactionSmokeTest.main(args);
        System.out.println("Mistake book service smoke checks passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected AppException");
        } catch (AppException expected) {
            // 参数或归属验证拒绝了请求。
        }
    }
}
