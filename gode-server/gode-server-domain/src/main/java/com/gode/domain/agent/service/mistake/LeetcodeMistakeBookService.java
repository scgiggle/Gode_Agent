package com.gode.domain.agent.service.mistake;

import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.domain.agent.model.valobj.mistake.MistakeBookPageVO;
import com.gode.domain.agent.model.valobj.mistake.MistakeDetailVO;
import com.gode.domain.agent.model.valobj.mistake.SaveMistakeCommand;
import com.gode.domain.agent.service.ILeetcodeMistakeBookService;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class LeetcodeMistakeBookService implements ILeetcodeMistakeBookService {
    private static final String PENDING = "待复习";
    private static final String REVIEWING = "复习中";
    private static final String MASTERED = "已掌握";
    private static final Set<String> STATUSES = Set.of(PENDING, REVIEWING, MASTERED);
    private static final Set<String> DIFFICULTIES = Set.of("简单", "中等", "困难");

    @Resource
    private ILeetcodeMistakeRepository mistakeRepository;

    /**
     * 保存用户确认的错题草稿，返回错题 ID。
     * 同一用户、题号复用主记录并重置为待复习，每次保存追加复盘历史；两张表在同一事务内写入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveMistake(String userId, SaveMistakeCommand command) {
        String owner = required(userId, "userId", 64);
        if (command == null) throw invalid("错题内容不能为空");
        String problemId = required(command.getProblemId(), "problemId", 64);
        String slug = required(command.getProblemSlug(), "problemSlug", 128);
        String title = required(command.getTitle(), "title", 200);
        String difficulty = required(command.getDifficulty(), "difficulty", 20);
        if (!DIFFICULTIES.contains(difficulty)) throw invalid("难度必须为简单、中等或困难");
        String tags = optional(command.getTags(), "tags", 500);
        String sessionId = optional(command.getSourceSessionId(), "sourceSessionId", 64);

        LocalDateTime now = LocalDateTime.now();
        LeetcodeMistakeEntity mistake = mistakeRepository.queryByUserAndProblemId(owner, problemId);
        boolean isNew = mistake == null;
        if (isNew) {
            mistake = LeetcodeMistakeEntity.builder()
                    .userId(owner).problemId(problemId).reviewCount(0).createdAt(now).build();
        }
        mistake.setProblemSlug(slug);
        mistake.setTitle(title);
        mistake.setDifficulty(difficulty);
        mistake.setErrorSummary(text(command.getErrorSummary()));
        mistake.setTags(tags);
        mistake.setSourceSessionId(sessionId);
        mistake.setStatus(PENDING);
        mistake.setNextReviewTime(now);
        mistake.setUpdatedAt(now);
        if (isNew) mistakeRepository.insertMistake(mistake);
        else mistakeRepository.updateMistake(mistake);

        mistakeRepository.insertReview(LeetcodeMistakeReviewEntity.builder()
                .mistakeId(mistake.getId())
                .failReason(text(command.getFailReason()))
                .failCase(text(command.getFailCase()))
                .aiAnalysis(text(command.getAiAnalysis()))
                .actionPlan(text(command.getActionPlan()))
                .sourceSessionId(sessionId).createdAt(now).build());
        log.info("保存错题 userId={} mistakeId={} problemId={} newRecord={}",
                owner, mistake.getId(), problemId, isNew);
        return mistake.getId();
    }

    /**
     * 按用户分页查询错题，可按状态、标签和关键词筛选。
     * 默认查询第 1 页、每页 10 条，返回列表、总条数和分页信息。
     */
    @Override
    public MistakeBookPageVO queryPage(String userId, String status, String tag,
                                     String keyword, Integer page, Integer pageSize) {
        String owner = required(userId, "userId", 64);
        String filterStatus = optional(status, "status", 20);
        if (filterStatus != null && !STATUSES.contains(filterStatus)) throw invalid("不支持的错题状态");
        String filterTag = optional(tag, "tag", 500);
        String filterKeyword = optional(keyword, "keyword", 500);
        int currentPage = page == null ? 1 : page;
        int size = pageSize == null ? 10 : pageSize;
        if (currentPage < 1 || size < 1 || size > 100) throw invalid("page 至少为 1，pageSize 必须为 1~100");
        long offset = ((long) currentPage - 1) * size;
        if (offset > Integer.MAX_VALUE) throw invalid("页码过大");
        long total = mistakeRepository.countPage(owner, filterStatus, filterTag, filterKeyword);
        List<LeetcodeMistakeEntity> records = offset >= total ? List.of()
                : mistakeRepository.queryPage(owner, filterStatus, filterTag, filterKeyword, (int) offset, size);
        return MistakeBookPageVO.builder()
                .records(records == null ? List.of() : records).total(total)
                .page(currentPage).pageSize(size)
                .totalPages(total / size + (total % size == 0 ? 0 : 1)).build();
    }

    /** 校验错题归属后查询全部复盘；仓储按创建时间、ID 倒序，首条即最新复盘。 */
    @Override
    public MistakeDetailVO queryDetail(String userId, Long mistakeId) {
        LeetcodeMistakeEntity mistake = ownedMistake(userId, mistakeId);
        List<LeetcodeMistakeReviewEntity> reviews = mistakeRepository.queryReviewList(mistakeId);
        if (reviews == null) reviews = List.of();
        log.info("查询错题复盘历史 userId={} mistakeId={} reviewCount={}",
                mistake.getUserId(), mistakeId, reviews.size());
        return MistakeDetailVO.builder().mistake(mistake)
                .reviews(reviews)
                .latestReview(reviews.isEmpty() ? null : reviews.get(0)).build();
    }

    /** 将错题标记为复习中、复习时间设为当前时间；不增加完成次数，已在复习中则不重复更新。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startReview(String userId, Long mistakeId) {
        LeetcodeMistakeEntity mistake = ownedMistake(userId, mistakeId);
        if (REVIEWING.equals(mistake.getStatus())) return;
        mistake.setStatus(REVIEWING);
        mistake.setNextReviewTime(LocalDateTime.now());
        mistake.setUpdatedAt(LocalDateTime.now());
        mistakeRepository.updateMistake(mistake);
        log.info("开始复习 userId={} mistakeId={}", mistake.getUserId(), mistakeId);
    }

    /** 将错题标记为已掌握，完成次数加 1 并清空复习时间；已经掌握则直接返回。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMastered(String userId, Long mistakeId) {
        LeetcodeMistakeEntity mistake = ownedMistake(userId, mistakeId);
        if (MASTERED.equals(mistake.getStatus())) return;
        int count = mistake.getReviewCount() == null ? 0 : mistake.getReviewCount();
        if (count == Integer.MAX_VALUE) throw invalid("复习次数已达到上限");
        mistake.setStatus(MASTERED);
        mistake.setReviewCount(count + 1);
        mistake.setNextReviewTime(null);
        mistake.setUpdatedAt(LocalDateTime.now());
        mistakeRepository.updateMistake(mistake);
        log.info("标记掌握 userId={} mistakeId={} reviewCount={}",
                mistake.getUserId(), mistakeId, mistake.getReviewCount());
    }

    /** 先校验归属，再在同一事务内先删复盘、后删主记录；主记录未成功删除也要回滚。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMistake(String userId, Long mistakeId) {
        LeetcodeMistakeEntity mistake = ownedMistake(userId, mistakeId);
        String owner = mistake.getUserId();
        int historyCount = mistakeRepository.deleteReviewsByMistakeId(mistakeId, owner);
        if (mistakeRepository.deleteMistakeByIdAndUserId(mistakeId, owner) != 1) {
            throw invalid("错题删除失败，请刷新后重试");
        }
        log.info("删除错题 userId={} mistakeId={} deletedHistoryCount={}", owner, mistakeId, historyCount);
    }

    /** 校验用户 ID 和错题 ID，查询当前用户拥有的错题；不存在或不属于该用户时抛出异常。 */
    private LeetcodeMistakeEntity ownedMistake(String userId, Long mistakeId) {
        String owner = required(userId, "userId", 64);
        if (mistakeId == null || mistakeId <= 0) throw invalid("mistakeId 必须为正整数");
        LeetcodeMistakeEntity mistake = mistakeRepository.queryByIdAndUserId(mistakeId, owner);
        if (mistake == null) throw invalid("错题不存在或不属于当前用户");
        return mistake;
    }

    /** 校验必填字符串：去除首尾空白，检查非空和最大长度，返回处理后的值。 */
    private String required(String value, String name, int maxLength) {
        String result = optional(value, name, maxLength);
        if (result == null) throw invalid(name + " 不能为空");
        return result;
    }

    /** 处理可选字符串：空值返回 null，非空值去除首尾空白并校验最大长度。 */
    private String optional(String value, String name, int maxLength) {
        String result = text(value);
        if (result != null && result.length() > maxLength) throw invalid(name + " 长度不能超过 " + maxLength);
        return result;
    }

    /** 统一处理文本：null 或纯空白转为 null，其余文本去除首尾空白。 */
    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 使用非法参数错误码和指定提示构造业务异常，由调用处抛出。 */
    private AppException invalid(String message) {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
    }
}
