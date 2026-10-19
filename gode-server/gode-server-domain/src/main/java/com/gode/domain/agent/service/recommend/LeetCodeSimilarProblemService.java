package com.gode.domain.agent.service.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import com.gode.domain.agent.model.valobj.recommend.SimilarProblemCandidatesVO;
import com.gode.domain.agent.service.ISimilarProblemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 真实相似题来源；候选标签批量查询失败时仍返回原相似题，不编造标签。 */
@Slf4j
@Service
public class LeetCodeSimilarProblemService implements ISimilarProblemService {
    private static final String GRAPHQL_URL = "https://leetcode.com/graphql/";
    private static final String QUESTION_QUERY = """
            query questionData($titleSlug: String!) {
              question(titleSlug: $titleSlug) {
                titleSlug difficulty similarQuestions
                topicTags { name slug }
              }
            }
            """;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public LeetCodeSimilarProblemService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** 保留原来的最多五题接口。 */
    @Override
    public List<SimilarProblemVO> recommend(String currentProblemSlug, int count) {
        return candidates(currentProblemSlug, Math.max(1, Math.min(count, 5))).candidates();
    }

    /** 最多二十个候选，再用一次批量 GraphQL 补齐真实知识点和难度。 */
    @Override
    public SimilarProblemCandidatesVO candidates(String currentProblemSlug, int limit) {
        if (currentProblemSlug == null || !currentProblemSlug.matches("[a-z0-9-]{1,120}")) {
            throw new IllegalArgumentException("currentProblemSlug 格式不正确");
        }
        int size = Math.max(1, Math.min(limit, 20));
        log.info("[LeetCodeSimilarProblemService] 开始查询相似题候选 currentProblemSlug={} limit={}", currentProblemSlug, size);
        try {
            JsonNode root = postGraphql(Map.of("operationName", "questionData",
                    "variables", Map.of("titleSlug", currentProblemSlug), "query", QUESTION_QUERY), currentProblemSlug);
            JsonNode question = root.path("data").path("question");
            if (question.isMissingNode() || question.isNull()) throw new IllegalArgumentException("没有找到题目：" + currentProblemSlug);
            JsonNode similar = question.path("similarQuestions");
            if (similar.isTextual()) similar = objectMapper.readTree(similar.asText());
            if (similar == null || !similar.isArray()) throw new IllegalStateException("力扣相似题字段格式异常");
            List<SimilarProblemVO> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add(currentProblemSlug);
            for (JsonNode item : similar) {
                String slug = item.path("titleSlug").asText("");
                if (!slug.matches("[a-z0-9-]{1,120}") || !seen.add(slug)) continue;
                String translated = item.path("translatedTitle").asText("");
                String title = translated.isBlank() ? item.path("title").asText("") : translated;
                if (title.isBlank()) continue;
                result.add(SimilarProblemVO.builder().title(title).titleSlug(slug)
                        .difficulty(translateDifficulty(item.path("difficulty").asText("")))
                        .url("https://leetcode.cn/problems/" + slug + "/").topicTags(List.of()).build());
                if (result.size() >= size) break;
            }
            boolean metadata = enrichCandidates(result, currentProblemSlug);
            log.info("[LeetCodeSimilarProblemService] 相似题候选解析完成 currentProblemSlug={} resultCount={} metadataAvailable={}",
                    currentProblemSlug, result.size(), metadata);
            return new SimilarProblemCandidatesVO(translateDifficulty(question.path("difficulty").asText("")),
                    topicTags(question), List.copyOf(result), metadata);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("[LeetCodeSimilarProblemService] 查询力扣相似题异常 currentProblemSlug={}", currentProblemSlug, e);
            throw new IllegalStateException("查询力扣相似题失败：" + e.getMessage(), e);
        }
    }

    /** 标签查询失败仅降级，绝不将当前题的标签直接复制给所有候选题。 */
    private boolean enrichCandidates(List<SimilarProblemVO> candidates, String currentSlug) throws InterruptedException {
        if (candidates.isEmpty()) return false;
        StringJoiner definitions = new StringJoiner(", ");
        StringBuilder selections = new StringBuilder();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            definitions.add("$slug" + i + ": String!");
            variables.put("slug" + i, candidates.get(i).getTitleSlug());
            selections.append("q").append(i).append(": question(titleSlug: $slug").append(i)
                    .append(") { titleSlug difficulty topicTags { name slug } }\n");
        }
        try {
            JsonNode data = postGraphql(Map.of("operationName", "recommendationCandidates", "variables", variables,
                    "query", "query recommendationCandidates(" + definitions + ") {\n" + selections + "}"), currentSlug).path("data");
            int covered = 0;
            for (int i = 0; i < candidates.size(); i++) {
                SimilarProblemVO candidate = candidates.get(i);
                JsonNode item = data.path("q" + i);
                if (!candidate.getTitleSlug().equals(item.path("titleSlug").asText())) continue;
                List<String> tags = topicTags(item);
                candidate.setTopicTags(tags);
                String difficulty = translateDifficulty(item.path("difficulty").asText(""));
                if (!difficulty.isBlank()) candidate.setDifficulty(difficulty);
                if (!tags.isEmpty()) covered++;
            }
            return covered > 0;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[LeetCodeSimilarProblemService] 候选标签补查失败，保留相似题 currentProblemSlug={} error={}", currentSlug, e.toString());
            return false;
        }
    }

    private List<String> topicTags(JsonNode question) {
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode tag : question.path("topicTags")) {
            String name = tag.path("slug").asText("");
            if (name.isBlank()) name = tag.path("name").asText("");
            if (!name.isBlank()) tags.add(name);
        }
        return List.copyOf(tags);
    }

    /** 只读网络边界，测试通过替身验证请求和解析，不访问真实力扣。 */
    protected JsonNode postGraphql(Map<String, Object> body, String slug) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(GRAPHQL_URL)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("User-Agent", "Mozilla/5.0")
                .header("Origin", "https://leetcode.com").header("Referer", "https://leetcode.com/problems/" + slug + "/")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log.info("[LeetCodeSimilarProblemService] GraphQL 请求完成 currentProblemSlug={} operation={} httpStatus={}",
                slug, body.get("operationName"), response.statusCode());
        if (response.statusCode() != 200) throw new IllegalStateException("力扣接口请求失败，HTTP " + response.statusCode());
        JsonNode root = objectMapper.readTree(response.body());
        if (root == null) throw new IllegalStateException("力扣响应为空");
        if (root.path("errors").isArray() && !root.path("errors").isEmpty()) throw new IllegalStateException("力扣 GraphQL 返回错误");
        return root;
    }

    private String translateDifficulty(String difficulty) {
        return switch (difficulty) { case "Easy" -> "简单"; case "Medium" -> "中等"; case "Hard" -> "困难"; default -> difficulty; };
    }
}
