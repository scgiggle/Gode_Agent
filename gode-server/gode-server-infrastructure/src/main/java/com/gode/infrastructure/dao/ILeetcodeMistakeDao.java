package com.gode.infrastructure.dao;

import com.gode.infrastructure.dao.po.LeetcodeMistakePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ILeetcodeMistakeDao {

    List<LeetcodeMistakePO> queryRecentLearningMistakes(@Param("userId") String userId, @Param("limit") int limit);

    List<LeetcodeMistakePO> queryByUserAndProblemSlugs(@Param("userId") String userId,
                                                   @Param("problemSlugs") List<String> problemSlugs);

    /**
     * 根据用户和力扣题号查询错题主记录。
     * <p>对应唯一索引 {@code uk_user_problem(user_id, problem_id)}。</p>
     */
    LeetcodeMistakePO queryByUserAndProblemId(
            @Param("userId") String userId,
            @Param("problemId") String problemId
    );

    /**
     * 查询用户自己的某条错题记录，详情、状态更新前都应使用此方法校验归属。
     */
    LeetcodeMistakePO queryByIdAndUserId(
            @Param("id") Long id,
            @Param("userId") String userId
    );

    /**
     * 新增错题主记录；MyBatis Mapper 中应开启 useGeneratedKeys，使生成的主键回写 PO.id。
     */
    void insert(LeetcodeMistakePO po);

    /**
     * 更新错题当前状态、摘要、标签、复习时间等字段。
     */
    int update(LeetcodeMistakePO po);

    /** 按主键和用户 ID 删除主记录；返回影响条数。 */
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") String userId);

    /**
     * 错题本分页列表查询。
     * status、tag、keyword 为空时，由 Mapper 的动态 SQL 忽略对应条件。
     */
    List<LeetcodeMistakePO> queryPage(
            @Param("userId") String userId,
            @Param("status") String status,
            @Param("tag") String tag,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    /**
     * 查询满足筛选条件的总数量，用于计算总页数。
     */
    long countPage(
            @Param("userId") String userId,
            @Param("status") String status,
            @Param("tag") String tag,
            @Param("keyword") String keyword
    );
}
