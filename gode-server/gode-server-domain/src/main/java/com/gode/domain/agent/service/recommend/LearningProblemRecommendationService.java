package com.gode.domain.agent.service.recommend;

import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.domain.agent.model.valobj.SimilarProblemVO;
import com.gode.domain.agent.model.valobj.recommend.LearningRecommendationVO;
import com.gode.domain.agent.model.valobj.recommend.LearningRecommendationVO.*;
import com.gode.domain.agent.model.valobj.recommend.SimilarProblemCandidatesVO;
import com.gode.domain.agent.service.ISimilarProblemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** 用户级只读学习推荐：真实相似候选 + 错题/复盘规则排序，不增加一次 LLM 调用。 */
@Slf4j
@Service
public class LearningProblemRecommendationService {
    private static final String MASTERED = "已掌握";
    private static final int MAX_MISTAKES = 100;
    private static final int MAX_REVIEWS = 300;
    private final ISimilarProblemService similarProblemService;
    private final ILeetcodeMistakeRepository mistakeRepository;

    public LearningProblemRecommendationService(ISimilarProblemService similarProblemService,
                                               ILeetcodeMistakeRepository mistakeRepository) {
        this.similarProblemService = similarProblemService;
        this.mistakeRepository = mistakeRepository;
    }

    /** 用户身份只由工具执行上下文提供；不读取、合并其他 Session 的聊天消息。 */
    public LearningRecommendationVO recommend(String userId, String currentSlug, int count) {
        int size = Math.max(1, Math.min(count, 5));
        SimilarProblemCandidatesVO pool = similarProblemService.candidates(currentSlug, 20);
        List<SimilarProblemVO> candidates = cleanCandidates(pool.candidates(), currentSlug);
        Profile profile = new Profile();
        String status = "missing_user";
        Map<String, LeetcodeMistakeEntity> known = new LinkedHashMap<>();
        if (validUser(userId)) {
            try {
                List<LeetcodeMistakeEntity> recent = owned(
                        mistakeRepository.queryRecentLearningMistakes(userId, MAX_MISTAKES), userId);
                List<String> slugs = new ArrayList<>(candidates.stream().map(SimilarProblemVO::getTitleSlug).toList());
                slugs.add(currentSlug);
                for (LeetcodeMistakeEntity item : owned(mistakeRepository.queryByUserAndProblemSlugs(userId, slugs), userId)) {
                    if (item.getProblemSlug() != null) known.putIfAbsent(item.getProblemSlug(), item);
                }
                List<Long> ids = recent.stream().map(LeetcodeMistakeEntity::getId).filter(Objects::nonNull).distinct().toList();
                List<LeetcodeMistakeReviewEntity> reviews = ids.isEmpty() ? List.of()
                        : mistakeRepository.queryLearningReviews(userId, ids, MAX_REVIEWS);
                profile = buildProfile(recent, reviews);
                status = recent.isEmpty() && known.isEmpty() ? "no_history" : "available";
            } catch (Exception e) {
                // 部分查询失败时也丢弃半成品画像，不把失败当成“能力良好”。
                profile = new Profile();
                known.clear();
                status = "unavailable";
                log.warn("[LearningRecommendation] 学习记录读取失败，退回普通相似题 userId={} currentProblemSlug={} error={}",
                        userId, currentSlug, e.toString());
            }
        }

        List<Ranked> ranked = new ArrayList<>();
        int excluded = 0;
        int index = 0;
        boolean matched = false;
        for (SimilarProblemVO candidate : candidates) {
            LeetcodeMistakeEntity own = known.get(candidate.getTitleSlug());
            if (own != null && MASTERED.equals(own.getStatus())) { excluded++; continue; }
            Set<String> tags = topics(candidate.getTopicTags());
            List<String> weakMatches = tags.stream().filter(profile.topics::containsKey).sorted().toList();
            int weakScore = 0;
            for (String tag : weakMatches) {
                Stat stat = profile.topics.get(tag);
                // 通用数组/字符串标签权重低一些，避免压过具体算法。
                int weight = Set.of("数组", "字符串").contains(tag) ? 2 : 8;
                weakScore += weight * Math.min(3, stat.problems.size()) + Math.min(3, stat.reviewCount());
            }
            int reviewPriority = own != null && Set.of("待复习", "复习中").contains(nullSafe(own.getStatus())) ? 1 : 0;
            matched |= !weakMatches.isEmpty() || reviewPriority > 0;
            Set<String> focus = new LinkedHashSet<>();
            for (String topic : weakMatches) focus.addAll(profile.topicErrors.getOrDefault(topic, Set.of()));
            StringBuilder reason = new StringBuilder("力扣返回的当前题相似题");
            if (!weakMatches.isEmpty()) {
                reason.append("；匹配疑似薄弱知识点：");
                StringJoiner evidence = new StringJoiner("、");
                for (String topic : weakMatches) evidence.add(topic + "（" + profile.topics.get(topic).problems.size() + " 道未掌握错题）");
                reason.append(evidence);
            } else reason.append("；暂无与该候选标签匹配的薄弱点证据");
            if (reviewPriority > 0) reason.append("；该题在你的错题本中尚未掌握，适合再次练习");
            int currentDifficulty = difficulty(pool.currentDifficulty());
            int candidateDifficulty = difficulty(candidate.getDifficulty());
            int gap = currentDifficulty == 0 || candidateDifficulty == 0 ? 10 : Math.abs(currentDifficulty - candidateDifficulty);
            if (gap == 0) reason.append("；与当前题难度相同");
            SimilarProblemVO output = SimilarProblemVO.builder().title(candidate.getTitle()).titleSlug(candidate.getTitleSlug())
                    .url(candidate.getUrl()).difficulty(candidate.getDifficulty()).topicTags(List.copyOf(tags))
                    .matchedWeakTopics(weakMatches).recommendationReason(reason.toString())
                    .practiceFocus(focus.stream().limit(3).toList()).build();
            ranked.add(new Ranked(output, weakScore, reviewPriority, gap, index++));
        }
        boolean personalized = matched || excluded > 0;
        if (personalized) ranked.sort(Comparator.comparingInt(Ranked::weakScore).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::reviewPriority).reversed())
                .thenComparingInt(Ranked::difficultyGap).thenComparingInt(Ranked::index));
        List<SimilarProblemVO> result = ranked.stream().limit(size).map(Ranked::problem).toList();
        String message = switch (status) {
            case "missing_user" -> "无法取得实际用户身份，仅提供普通相似题。";
            case "unavailable" -> "学习记录暂不可用，已退回普通相似题。";
            case "no_history" -> "暂无错题学习记录，先提供普通相似题。";
            default -> personalized ? "已结合未掌握错题、复盘线索及难度排序；这不是准确掌握率。"
                    : "已有学习记录，但候选题暂未匹配到薄弱点证据，保留力扣相似题顺序。";
        };
        if (result.isEmpty() && excluded > 0) message += " 当前候选均已掌握，暂不重复推荐。";
        log.info("[LearningRecommendation] 推荐完成 userId={} currentProblemSlug={} learningDataStatus={} sampledProblems={} sampledReviews={} candidates={} excludedMastered={} personalized={} resultCount={}",
                userId, currentSlug, status, profile.sampledProblems, profile.sampledReviews,
                candidates.size(), excluded, personalized, result.size());
        return new LearningRecommendationVO(result, personalized, status, message, profile.sampledProblems,
                profile.sampledReviews, excluded, weakTopics(profile), errorPatterns(profile));
    }

    /** 按不同题目统计，而不是把同一道题多次复盘当成多道薄弱题。 */
    private Profile buildProfile(List<LeetcodeMistakeEntity> mistakes, List<LeetcodeMistakeReviewEntity> reviews) {
        Profile profile = new Profile();
        profile.sampledProblems = mistakes.size();
        Set<Long> ids = new HashSet<>(mistakes.stream().map(LeetcodeMistakeEntity::getId).filter(Objects::nonNull).toList());
        Map<Long, List<LeetcodeMistakeReviewEntity>> byMistake = new HashMap<>();
        Set<Long> seenReviews = new HashSet<>();
        for (LeetcodeMistakeReviewEntity review : reviews == null ? List.<LeetcodeMistakeReviewEntity>of() : reviews) {
            if (review == null || !ids.contains(review.getMistakeId()) || review.getId() == null || !seenReviews.add(review.getId())) continue;
            byMistake.computeIfAbsent(review.getMistakeId(), ignored -> new ArrayList<>()).add(review);
            profile.sampledReviews++;
        }
        for (LeetcodeMistakeEntity mistake : mistakes) {
            if (mistake.getId() == null || MASTERED.equals(mistake.getStatus())) continue;
            List<LeetcodeMistakeReviewEntity> history = byMistake.getOrDefault(mistake.getId(), List.of());
            Map<String, Integer> errors = new LinkedHashMap<>();
            if (history.isEmpty()) {
                for (String category : errorCategories(mistake.getErrorSummary())) errors.put(category, 0);
            } else {
                // 不分析 aiAnalysis/actionPlan，避免把“正确做法”误计为错误。
                for (LeetcodeMistakeReviewEntity review : history) {
                    for (String category : errorCategories(review.getFailReason())) errors.merge(category, 1, Integer::sum);
                }
            }
            for (String topic : topics(mistake.getTags() == null ? List.of()
                    : Arrays.asList(mistake.getTags().split("[,，;；\\n]+")))) {
                profile.topics.computeIfAbsent(topic, ignored -> new Stat()).add(mistake, history.size());
                profile.topicErrors.computeIfAbsent(topic, ignored -> new LinkedHashSet<>()).addAll(errors.keySet());
            }
            for (Map.Entry<String, Integer> error : errors.entrySet()) {
                profile.errors.computeIfAbsent(error.getKey(), ignored -> new Stat()).add(mistake, error.getValue());
            }
        }
        return profile;
    }

    private List<WeakTopic> weakTopics(Profile profile) {
        return sortedStats(profile.topics).stream().limit(5).map(e -> new WeakTopic(e.getKey(), e.getValue().problems.size(),
                e.getValue().reviewCount(), e.getValue().evidence())).toList();
    }
    private List<ErrorPattern> errorPatterns(Profile profile) {
        return sortedStats(profile.errors).stream().limit(5).map(e -> new ErrorPattern(e.getKey(), e.getValue().problems.size(),
                e.getValue().reviewCount(), e.getValue().evidence())).toList();
    }
    private List<Map.Entry<String, Stat>> sortedStats(Map<String, Stat> stats) {
        return stats.entrySet().stream().sorted(Comparator.<Map.Entry<String, Stat>>comparingInt(e -> e.getValue().problems.size()).reversed()
                .thenComparing(Comparator.comparingInt((Map.Entry<String, Stat> e) -> e.getValue().reviewCount()).reversed())
                .thenComparing(Map.Entry::getKey)).toList();
    }

    /** 不引入画像缓存，每次读取数据库最新状态；仓储外再守一次用户归属。 */
    private List<LeetcodeMistakeEntity> owned(List<LeetcodeMistakeEntity> records, String userId) {
        if (records == null) return List.of();
        Map<Long, LeetcodeMistakeEntity> unique = new LinkedHashMap<>();
        for (LeetcodeMistakeEntity item : records) {
            if (item != null && item.getId() != null && userId.equals(item.getUserId())) unique.putIfAbsent(item.getId(), item);
        }
        return unique.values().stream().limit(MAX_MISTAKES).toList();
    }
    private List<SimilarProblemVO> cleanCandidates(List<SimilarProblemVO> records, String currentSlug) {
        Map<String, SimilarProblemVO> unique = new LinkedHashMap<>();
        for (SimilarProblemVO item : records == null ? List.<SimilarProblemVO>of() : records) {
            if (item == null || item.getTitleSlug() == null || !item.getTitleSlug().matches("[a-z0-9-]{1,120}")
                    || currentSlug.equals(item.getTitleSlug()) || item.getTitle() == null || item.getTitle().isBlank()) continue;
            unique.putIfAbsent(item.getTitleSlug(), item);
        }
        return unique.values().stream().limit(20).toList();
    }
    private boolean validUser(String userId) {
        return userId != null && !userId.isBlank() && userId.length() <= 64
                && !Set.of("unknown", "subagent-user").contains(userId);
    }
    private int difficulty(String name) {
        return switch (nullSafe(name).toLowerCase(Locale.ROOT)) {
            case "简单", "easy" -> 1; case "中等", "medium" -> 2; case "困难", "hard" -> 3; default -> 0;
        };
    }
    private Set<String> topics(List<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        for (String raw : tags == null ? List.<String>of() : tags) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
            result.add(switch (name) {
                case "hash table", "hashmap", "哈希", "哈希表", "散列表" -> "哈希表";
                case "two pointers", "双指针" -> "双指针";
                case "sliding window", "滑动窗口" -> "滑动窗口";
                case "dynamic programming", "dp", "动态规划" -> "动态规划";
                case "binary search", "二分", "二分查找" -> "二分查找";
                case "array", "数组" -> "数组"; case "string", "字符串" -> "字符串";
                case "linked list", "链表" -> "链表"; case "stack", "栈" -> "栈";
                case "queue", "队列" -> "队列"; case "tree", "树" -> "树";
                case "binary tree", "二叉树" -> "二叉树";
                case "depth first search", "dfs", "深度优先搜索" -> "深度优先搜索";
                case "breadth first search", "bfs", "广度优先搜索" -> "广度优先搜索";
                case "backtracking", "回溯" -> "回溯"; case "greedy", "贪心" -> "贪心";
                case "sorting", "排序" -> "排序"; case "prefix sum", "前缀和" -> "前缀和";
                case "heap priority queue", "heap (priority queue)", "heap", "堆", "堆（优先队列）" -> "堆（优先队列）";
                case "graph", "图" -> "图"; case "union find", "并查集" -> "并查集";
                case "bit manipulation", "位运算" -> "位运算"; default -> name;
            });
        }
        return result;
    }
    private static final Map<String, Pattern> ERROR_RULES = new LinkedHashMap<>();
    static {
        ERROR_RULES.put("下标与元素复用", Pattern.compile("自匹配|复用下标|下标复用|重复使用|同一元素|先插入|先存入|越界|下标错误"));
        ERROR_RULES.put("边界与特殊输入", Pattern.compile("边界|空数组|空字符串|重复元素|负数|单个元素|空指针"));
        ERROR_RULES.put("复杂度与性能", Pattern.compile("超时|时间复杂度过高|复杂度过高|超内存|重复计算|timeout|time limit", Pattern.CASE_INSENSITIVE));
        ERROR_RULES.put("状态与转移", Pattern.compile("状态转移|转移方程|初始化错误|递推错误|递归终止|递归出口"));
        ERROR_RULES.put("数据类型与溢出", Pattern.compile("溢出|类型转换错误|精度丢失|overflow", Pattern.CASE_INSENSITIVE));
        ERROR_RULES.put("实现与语法", Pattern.compile("编译错误|语法错误|变量未定义|compile error", Pattern.CASE_INSENSITIVE));
    }
    private Set<String> errorCategories(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) return result;
        String bounded = text.substring(0, Math.min(text.length(), 4000));
        ERROR_RULES.forEach((category, pattern) -> {
            var matcher = pattern.matcher(bounded);
            while (matcher.find()) {
                String prefix = bounded.substring(Math.max(0, matcher.start() - 10), matcher.start());
                if (prefix.matches("(?s).*(?:不是|并非|没有|无需|未出现)[^。；，\\n]{0,6}")) continue;
                result.add(category); break;
            }
        });
        return result;
    }
    private String nullSafe(String text) { return text == null ? "" : text; }
    private record Ranked(SimilarProblemVO problem, int weakScore, int reviewPriority, int difficultyGap, int index) { }
    private static class Profile {
        int sampledProblems, sampledReviews;
        final Map<String, Stat> topics = new LinkedHashMap<>(), errors = new LinkedHashMap<>();
        final Map<String, Set<String>> topicErrors = new LinkedHashMap<>();
    }
    private static class Stat {
        final Map<Long, Integer> problems = new LinkedHashMap<>();
        final Set<String> slugs = new LinkedHashSet<>();
        void add(LeetcodeMistakeEntity mistake, int reviews) {
            problems.put(mistake.getId(), reviews);
            if (mistake.getProblemSlug() != null && !mistake.getProblemSlug().isBlank()) slugs.add(mistake.getProblemSlug());
        }
        int reviewCount() { return problems.values().stream().mapToInt(Integer::intValue).sum(); }
        List<String> evidence() { return slugs.stream().limit(3).toList(); }
    }
}
