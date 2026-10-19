package com.gode.domain.agent.service.armory.matter.tools;

import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import com.gode.domain.agent.service.recommend.LearningProblemRecommendationService;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Service
public class SimilarProblemTool {

    @Resource
    private LearningProblemRecommendationService learningRecommendationService;

    @Schema(description = "查询真实力扣相似题，并结合实际用户的未掌握错题、近期复盘线索、已掌握状态及难度排序；学习数据不足时退回普通相似题。")
    public Map<String, Object> recommendSimilarProblems(
            @Schema(
                    name = "currentProblemSlug",
                    description = """
                            当前力扣题目的 titleSlug，
                            例如 two-sum。
                            必须从当前题目上下文中获取，
                            不能根据中文标题猜测
                            """
            )
            String currentProblemSlug,

            @Schema(
                    name = "count",
                    optional = true,
                    description = """
                            推荐题目数量，
                            最小为 1，最大为 5
                            """
            )
            Integer count,
            @Schema(name = "toolContext") ToolContext toolContext) {

        log.info("[SimilarProblemTool] 工具被调用 currentProblemSlug={} count={}",
                currentProblemSlug, count);

        if (currentProblemSlug == null
                || currentProblemSlug.isBlank()) {

            log.warn("[SimilarProblemTool] 调用失败：currentProblemSlug 为空");

            return Map.of(
                    "success", false,
                    "message", "currentProblemSlug 不能为空"
            );
        }

        if (!currentProblemSlug.matches(
                "[a-z0-9-]{1,120}")) {

            log.warn("[SimilarProblemTool] 调用失败：currentProblemSlug 格式不正确 value={}",
                    currentProblemSlug);

            return Map.of(
                    "success", false,
                    "message", "currentProblemSlug 格式不正确"
            );
        }

        int safeCount = count == null
                ? 3
                : Math.max(1, Math.min(count, 5));

        try {
            // ADK 注入执行上下文，不向模型暴露 userId 参数。
            String userId = toolContext == null ? null : toolContext.userId();
            var result = learningRecommendationService.recommend(userId, currentProblemSlug, safeCount);
            List<SimilarProblemVO> problems = result.recommendations();

            log.info("[SimilarProblemTool] 工具调用成功 currentProblemSlug={} requestedCount={} resultCount={}",
                    currentProblemSlug, safeCount, problems.size());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("currentProblemSlug", currentProblemSlug);
            response.put("count", problems.size());
            response.put("recommendations", problems);
            response.put("personalized", result.personalized());
            response.put("learningDataStatus", result.learningDataStatus());
            response.put("message", result.message());
            response.put("sampledProblemCount", result.sampledProblemCount());
            response.put("sampledReviewCount", result.sampledReviewCount());
            response.put("excludedMasteredCount", result.excludedMasteredCount());
            response.put("weakTopics", result.weakTopics());
            response.put("errorPatterns", result.errorPatterns());
            return response;
        } catch (Exception e) {
            log.error("[SimilarProblemTool] 工具调用异常 currentProblemSlug={} count={}",
                    currentProblemSlug, safeCount, e);

            return Map.of(
                    "success", false,
                    "message",
                    String.valueOf(e.getMessage())
            );
        }
    }
}
