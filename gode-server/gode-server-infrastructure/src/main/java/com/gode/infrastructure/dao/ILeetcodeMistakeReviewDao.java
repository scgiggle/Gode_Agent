package com.gode.infrastructure.dao;

import com.gode.infrastructure.dao.po.LeetcodeMistakeReviewPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ILeetcodeMistakeReviewDao {

    List<LeetcodeMistakeReviewPO> queryLearningReviews(@Param("userId") String userId,
            @Param("mistakeIds") List<Long> mistakeIds, @Param("limit") int limit);

    /**
     * 新增一次错题复盘历史，不覆盖旧的失败记录。
     */
    void insert(LeetcodeMistakeReviewPO po);

    /**
     * 查询某道错题最新的一次复盘，用于错题详情页默认展示。
     */
    LeetcodeMistakeReviewPO queryLatestByMistakeId(
            @Param("mistakeId") Long mistakeId
    );

    /**
     * 查询某道错题的全部复盘历史，按创建时间倒序。
     */
    List<LeetcodeMistakeReviewPO> queryListByMistakeId(
            @Param("mistakeId") Long mistakeId
    );

    /** 通过主表用户归属约束，删除该道错题的全部复盘。 */
    int deleteByMistakeIdAndUserId(@Param("mistakeId") Long mistakeId, @Param("userId") String userId);
}
