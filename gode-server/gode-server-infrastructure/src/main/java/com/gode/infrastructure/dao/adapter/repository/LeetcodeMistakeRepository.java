package com.gode.infrastructure.dao.adapter.repository;

import com.gode.domain.agent.adapter.repository.ILeetcodeMistakeRepository;
import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;
import com.gode.infrastructure.dao.ILeetcodeMistakeDao;
import com.gode.infrastructure.dao.ILeetcodeMistakeReviewDao;
import com.gode.infrastructure.dao.po.LeetcodeMistakePO;
import com.gode.infrastructure.dao.po.LeetcodeMistakeReviewPO;
import com.gode.types.enums.ResponseCode;
import com.gode.types.exception.AppException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class LeetcodeMistakeRepository implements ILeetcodeMistakeRepository {

    @Resource
    private ILeetcodeMistakeDao leetcodeMistakeDao;

    @Resource
    private ILeetcodeMistakeReviewDao leetcodeMistakeReviewDao;

    @Override
    public LeetcodeMistakeEntity queryByUserAndProblemId(String userId, String problemId) {
        return toEntity(leetcodeMistakeDao.queryByUserAndProblemId(userId, problemId));
    }

    @Override
    public LeetcodeMistakeEntity queryByIdAndUserId(Long id, String userId) {
        return toEntity(leetcodeMistakeDao.queryByIdAndUserId(id, userId));
    }

    @Override
    public void insertMistake(LeetcodeMistakeEntity mistake) {
        LeetcodeMistakePO po = toPO(mistake);
        leetcodeMistakeDao.insert(po);
        // Mapper 的 useGeneratedKeys 回写 PO 后，再传回领域实体，供复盘关联使用。
        mistake.setId(po.getId());
    }

    @Override
    public void updateMistake(LeetcodeMistakeEntity mistake) {
        // 并发删除后更新可能影响 0 行，此时不能继续插入复盘，避免产生孤立历史。
        if (leetcodeMistakeDao.update(toPO(mistake)) != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "错题不存在或已被删除，请刷新后重试");
        }
    }

    @Override
    public List<LeetcodeMistakeEntity> queryPage(
            String userId, String status, String tag,
            String keyword, int offset, int pageSize) {
        List<LeetcodeMistakePO> records =
                leetcodeMistakeDao.queryPage(userId, status, tag, keyword, offset, pageSize);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public long countPage(String userId, String status, String tag, String keyword) {
        return leetcodeMistakeDao.countPage(userId, status, tag, keyword);
    }

    @Override
    public List<LeetcodeMistakeEntity> queryRecentLearningMistakes(String userId, int limit) {
        return leetcodeMistakeDao.queryRecentLearningMistakes(userId, Math.max(1, Math.min(limit, 100)))
                .stream().map(this::toEntity).toList();
    }

    @Override
    public List<LeetcodeMistakeEntity> queryByUserAndProblemSlugs(String userId, List<String> problemSlugs) {
        if (problemSlugs == null || problemSlugs.isEmpty()) return List.of();
        return leetcodeMistakeDao.queryByUserAndProblemSlugs(userId, problemSlugs.stream().distinct().limit(21).toList())
                .stream().map(this::toEntity).toList();
    }

    @Override
    public List<LeetcodeMistakeReviewEntity> queryLearningReviews(String userId, List<Long> mistakeIds, int limit) {
        if (mistakeIds == null || mistakeIds.isEmpty()) return List.of();
        return leetcodeMistakeReviewDao.queryLearningReviews(userId, mistakeIds.stream().distinct().limit(100).toList(),
                Math.max(1, Math.min(limit, 300))).stream().map(this::toEntity).toList();
    }

    @Override
    public void insertReview(LeetcodeMistakeReviewEntity review) {
        LeetcodeMistakeReviewPO po = toPO(review);
        leetcodeMistakeReviewDao.insert(po);
        review.setId(po.getId());
    }

    @Override
    public LeetcodeMistakeReviewEntity queryLatestReview(Long mistakeId) {
        return toEntity(leetcodeMistakeReviewDao.queryLatestByMistakeId(mistakeId));
    }

    @Override
    public List<LeetcodeMistakeReviewEntity> queryReviewList(Long mistakeId) {
        List<LeetcodeMistakeReviewPO> records =
                leetcodeMistakeReviewDao.queryListByMistakeId(mistakeId);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream().map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public int deleteReviewsByMistakeId(Long mistakeId, String userId) {
        return leetcodeMistakeReviewDao.deleteByMistakeIdAndUserId(mistakeId, userId);
    }

    @Override
    public int deleteMistakeByIdAndUserId(Long mistakeId, String userId) {
        return leetcodeMistakeDao.deleteByIdAndUserId(mistakeId, userId);
    }

    private LeetcodeMistakePO toPO(LeetcodeMistakeEntity entity) {
        return LeetcodeMistakePO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .problemId(entity.getProblemId())
                .problemSlug(entity.getProblemSlug())
                .title(entity.getTitle())
                .difficulty(entity.getDifficulty())
                .errorSummary(entity.getErrorSummary())
                .tags(entity.getTags())
                .status(entity.getStatus())
                .nextReviewTime(entity.getNextReviewTime())
                .reviewCount(entity.getReviewCount() == null ? 0 : entity.getReviewCount())
                .sourceSessionId(entity.getSourceSessionId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private LeetcodeMistakeEntity toEntity(LeetcodeMistakePO po) {
        if (po == null) {
            return null;
        }
        return LeetcodeMistakeEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .problemId(po.getProblemId())
                .problemSlug(po.getProblemSlug())
                .title(po.getTitle())
                .difficulty(po.getDifficulty())
                .errorSummary(po.getErrorSummary())
                .tags(po.getTags())
                .status(po.getStatus())
                .nextReviewTime(po.getNextReviewTime())
                .reviewCount(po.getReviewCount())
                .sourceSessionId(po.getSourceSessionId())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private LeetcodeMistakeReviewPO toPO(LeetcodeMistakeReviewEntity entity) {
        return LeetcodeMistakeReviewPO.builder()
                .id(entity.getId())
                .mistakeId(entity.getMistakeId())
                .failReason(entity.getFailReason())
                .failCase(entity.getFailCase())
                .aiAnalysis(entity.getAiAnalysis())
                .actionPlan(entity.getActionPlan())
                .sourceSessionId(entity.getSourceSessionId())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private LeetcodeMistakeReviewEntity toEntity(LeetcodeMistakeReviewPO po) {
        if (po == null) {
            return null;
        }
        return LeetcodeMistakeReviewEntity.builder()
                .id(po.getId())
                .mistakeId(po.getMistakeId())
                .failReason(po.getFailReason())
                .failCase(po.getFailCase())
                .aiAnalysis(po.getAiAnalysis())
                .actionPlan(po.getActionPlan())
                .sourceSessionId(po.getSourceSessionId())
                .createdAt(po.getCreatedAt())
                .build();
    }
}
