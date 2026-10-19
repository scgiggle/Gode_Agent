package com.gode.domain.agent.adapter.repository;

import com.gode.domain.agent.model.entity.LeetcodeMistakeEntity;
import com.gode.domain.agent.model.entity.LeetcodeMistakeReviewEntity;

import java.util.List;

public interface ILeetcodeMistakeRepository {

    LeetcodeMistakeEntity queryByUserAndProblemId(String userId, String problemId);

    /** 只查询当前用户拥有的错题。 */
    LeetcodeMistakeEntity queryByIdAndUserId(Long id, String userId);

    /** 新增后将数据库生成的 ID 回写到实体。 */
    void insertMistake(LeetcodeMistakeEntity mistake);

    /** 更新时要求实体中同时包含 ID 和 userId。 */
    void updateMistake(LeetcodeMistakeEntity mistake);

    List<LeetcodeMistakeEntity> queryPage(
            String userId, String status, String tag,
            String keyword, int offset, int pageSize
    );

    long countPage(String userId, String status, String tag, String keyword);

    /** 用户最近更新的错题样本，用于学习推荐，不取其他会话历史。 */
    List<LeetcodeMistakeEntity> queryRecentLearningMistakes(String userId, int limit);

    /** 精确查询候选题状态，避免已掌握记录在样本范围外而被遗漏。 */
    List<LeetcodeMistakeEntity> queryByUserAndProblemSlugs(String userId, List<String> problemSlugs);

    /** 批量读取样本的近期复盘；仓储 SQL 再校验用户归属。 */
    List<LeetcodeMistakeReviewEntity> queryLearningReviews(String userId, List<Long> mistakeIds, int limit);

    /** 新增后将数据库生成的 ID 回写到实体。 */
    void insertReview(LeetcodeMistakeReviewEntity review);

    /** 调用前应在 Service 校验 mistakeId 归属当前用户。 */
    LeetcodeMistakeReviewEntity queryLatestReview(Long mistakeId);

    /** 返回全部复盘，按 created_at DESC、id DESC；调用前由 Service 校验错题归属。 */
    List<LeetcodeMistakeReviewEntity> queryReviewList(Long mistakeId);

    /** 删除当前用户错题的全部复盘；返回删除条数，必须与主表删除处于同一事务。 */
    int deleteReviewsByMistakeId(Long mistakeId, String userId);

    /** 按 ID 和用户 ID 删除主记录，返回删除条数。 */
    int deleteMistakeByIdAndUserId(Long mistakeId, String userId);
}
