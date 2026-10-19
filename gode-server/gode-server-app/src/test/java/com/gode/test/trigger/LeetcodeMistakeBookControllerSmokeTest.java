package com.gode.test.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.domain.agent.model.valobj.mistake.MistakeBookPageVO;
import com.gode.domain.agent.model.valobj.mistake.MistakeDetailVO;
import com.gode.domain.agent.model.valobj.mistake.SaveMistakeCommand;
import com.gode.domain.agent.model.valobj.mistake.MistakeDraftVO;
import com.gode.domain.agent.service.ISubmissionReviewService;
import com.gode.domain.agent.service.ILeetcodeMistakeBookService;
import com.gode.trigger.http.LeetcodeMistakeBookController;
import com.gode.types.exception.AppException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 独立 main 冒烟检查：验证 HTTP 路由、JSON 和异常包装，不连接数据库。 */
public class LeetcodeMistakeBookControllerSmokeTest {
    public static void main(String[] args) throws Exception {
        StubService service = new StubService();
        LeetcodeMistakeBookController controller = new LeetcodeMistakeBookController();
        var field = LeetcodeMistakeBookController.class.getDeclaredField("mistakeBookService");
        field.setAccessible(true);
        field.set(controller, service);
        var analysisField = LeetcodeMistakeBookController.class.getDeclaredField("submissionReviewService");
        analysisField.setAccessible(true);
        analysisField.set(controller, (ISubmissionReviewService) command -> {
            check("SUBMITTED CODE".equals(command.getSubmittedCode()), "当次代码快照传入分析服务");
            check("WRONG_ANSWER".equals(command.getVerdict()), "真实判题状态");
            MistakeDraftVO draft = new MistakeDraftVO();
            draft.setProblemId(command.getProblemId()); draft.setProblemSlug(command.getProblemSlug());
            draft.setErrorSummary("错误摘要"); draft.setAiAnalysis("**分析**");
            draft.setFailReason("错因"); draft.setFailCase(command.getFailCase()); draft.setActionPlan("检查边界");
            return draft;
        });
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode draft = json(mvc.perform(post("/api/v1/mistake/analyze_submission")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"demo-agent\",\"userId\":\"u1\",\"problemId\":\"1\","
                        + "\"problemSlug\":\"two-sum\",\"submittedCode\":\"SUBMITTED CODE\","
                        + "\"verdict\":\"WRONG_ANSWER\",\"failCase\":\"expected=[0,1]\"}"))
                .andReturn());
        check("0000".equals(draft.path("code").asText())
                && "**分析**".equals(draft.path("data").path("aiAnalysis").asText()), "草稿接口返回结构化分析");
        check(service.command == null, "分析草稿不会调用保存服务");

        JsonNode saved = json(mvc.perform(post("/api/v1/mistake/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u1\",\"problemId\":\"1\",\"problemSlug\":\"two-sum\","
                        + "\"title\":\"两数之和\",\"difficulty\":\"简单\",\"aiAnalysis\":\"分析\"}"))
                .andReturn());
        check("0000".equals(saved.path("code").asText()) && saved.path("data").asLong() == 7,
                "保存返回 ID");
        check("u1".equals(service.userId) && "two-sum".equals(service.command.getProblemSlug())
                && "分析".equals(service.command.getAiAnalysis()), "请求转换");

        JsonNode page = json(mvc.perform(get("/api/v1/mistake/page").param("userId", "u1")
                .param("status", "待复习").param("tag", "数组").param("keyword", "两数"))
                .andReturn());
        check(page.path("data").path("records").size() == 1 && service.page == 1
                && service.pageSize == 10 && "数组".equals(service.tag), "分页默认值和筛选");
        JsonNode detail = json(mvc.perform(get("/api/v1/mistake/detail")
                .param("userId", "u1").param("mistakeId", "7")).andReturn());
        check(detail.path("data").path("mistake").path("id").asLong() == 7
                && detail.path("data").path("latestReview").isNull()
                && detail.path("data").path("reviews").isArray()
                && detail.path("data").path("reviews").isEmpty(), "详情和空复盘数组");
        service.reviews = List.of(
                LeetcodeMistakeReviewEntity.builder().id(12L).mistakeId(7L)
                        .createdAt(LocalDateTime.of(2026, 10, 9, 12, 0))
                        .failReason("最新错因").failCase("最新用例").aiAnalysis("**最新分析**")
                        .actionPlan("1. 新计划").sourceSessionId("session-new").build(),
                LeetcodeMistakeReviewEntity.builder().id(11L).mistakeId(7L)
                        .createdAt(LocalDateTime.of(2026, 10, 8, 12, 0))
                        .failReason("旧错因").failCase("旧用例").aiAnalysis("**旧分析**")
                        .actionPlan("1. 旧计划").sourceSessionId("session-old").build());
        JsonNode historyDetail = json(mvc.perform(get("/api/v1/mistake/detail")
                .param("userId", "u1").param("mistakeId", "7")).andReturn()).path("data");
        JsonNode history = historyDetail.path("reviews");
        check(history.size() == 2 && history.get(0).path("id").asLong() == 12
                && historyDetail.path("latestReview").equals(history.get(0)), "JSON 包含完整倒序历史、兼容最新字段");
        JsonNode oldReview = history.get(1);
        check(oldReview.path("mistakeId").asLong() == 7
                && "旧错因".equals(oldReview.path("failReason").asText())
                && "旧用例".equals(oldReview.path("failCase").asText())
                && "**旧分析**".equals(oldReview.path("aiAnalysis").asText())
                && "1. 旧计划".equals(oldReview.path("actionPlan").asText())
                && "session-old".equals(oldReview.path("sourceSessionId").asText())
                && oldReview.hasNonNull("createdAt"), "每次复盘完整映射，包含时间与来源会话");
        String actionBody = "{\"userId\":\"u1\",\"mistakeId\":7}";
        check(json(mvc.perform(post("/api/v1/mistake/start_review")
                .contentType(MediaType.APPLICATION_JSON).content(actionBody)).andReturn())
                .path("data").asBoolean() && service.started, "开始复习");
        check(json(mvc.perform(post("/api/v1/mistake/mark_mastered")
                .contentType(MediaType.APPLICATION_JSON).content(actionBody)).andReturn())
                .path("data").asBoolean() && service.mastered, "标记掌握");
        check(json(mvc.perform(post("/api/v1/mistake/delete")
                .contentType(MediaType.APPLICATION_JSON).content(actionBody)).andReturn())
                .path("data").asBoolean() && "u1".equals(service.deletedUserId)
                && Long.valueOf(7).equals(service.deletedMistakeId), "删除接口传递用户身份和错题 ID");
        check(mvc.perform(get("/api/v1/mistake/delete")).andReturn().getResponse().getStatus() == 405,
                "GET 请求不得删除数据");
        check(mvc.perform(post("/api/v1/mistake/delete").contentType(MediaType.APPLICATION_JSON)
                .content("null")).andReturn().getResponse().getStatus() == 400, "空删除请求不执行");

        service.failure = new AppException("0002", "错题不存在或不属于当前用户");
        check("0002".equals(json(mvc.perform(post("/api/v1/mistake/delete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"u2\",\"mistakeId\":7}"))
                .andReturn()).path("code").asText()), "删除归属失败返回业务错误");
        check("0002".equals(json(mvc.perform(get("/api/v1/mistake/detail")
                .param("userId", "u2").param("mistakeId", "7")).andReturn()).path("code").asText()),
                "业务异常包装");
        service.failure = new IllegalStateException("数据库内部详情");
        JsonNode failed = json(mvc.perform(get("/api/v1/mistake/detail")
                .param("userId", "u1").param("mistakeId", "7")).andReturn());
        check("0001".equals(failed.path("code").asText())
                && !failed.toString().contains("数据库内部详情"), "不暴露内部异常");
        check(mvc.perform(get("/api/v1/mistake/detail").param("userId", "u1")
                .param("mistakeId", "bad")).andReturn().getResponse().getStatus() == 400,
                "非数字 ID 返回 400");
        System.out.println("Mistake book controller HTTP smoke checks passed");
    }

    private static JsonNode json(MvcResult result) throws Exception {
        check(result.getResponse().getStatus() == 200, "HTTP 状态");
        return new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static class StubService implements ILeetcodeMistakeBookService {
        String userId;
        String tag;
        Integer page;
        Integer pageSize;
        SaveMistakeCommand command;
        boolean started;
        boolean mastered;
        String deletedUserId;
        Long deletedMistakeId;
        RuntimeException failure;
        List<LeetcodeMistakeReviewEntity> reviews = List.of();
        final LeetcodeMistakeEntity mistake = LeetcodeMistakeEntity.builder().id(7L)
                .userId("u1").problemId("1").problemSlug("two-sum").title("两数之和")
                .createdAt(LocalDateTime.of(2026, 10, 9, 12, 0)).build();

        @Override
        public Long saveMistake(String userId, SaveMistakeCommand command) {
            this.userId = userId;
            this.command = command;
            return 7L;
        }

        @Override
        public MistakeBookPageVO queryPage(String userId, String status, String tag,
                                         String keyword, Integer page, Integer pageSize) {
            this.tag = tag;
            this.page = page;
            this.pageSize = pageSize;
            return MistakeBookPageVO.builder().records(List.of(mistake)).total(1L)
                    .page(page).pageSize(pageSize).totalPages(1L).build();
        }

        @Override
        public MistakeDetailVO queryDetail(String userId, Long mistakeId) {
            if (failure != null) throw failure;
            return MistakeDetailVO.builder().mistake(mistake).reviews(reviews)
                    .latestReview(reviews.isEmpty() ? null : reviews.get(0)).build();
        }

        @Override
        public void startReview(String userId, Long mistakeId) { started = true; }

        @Override
        public void markMastered(String userId, Long mistakeId) { mastered = true; }

        @Override
        public void deleteMistake(String userId, Long mistakeId) {
            if (failure != null) throw failure;
            deletedUserId = userId;
            deletedMistakeId = mistakeId;
        }
    }
}
