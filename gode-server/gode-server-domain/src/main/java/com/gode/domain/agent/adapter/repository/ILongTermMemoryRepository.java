package com.gode.domain.agent.adapter.repository;

import com.gode.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

public interface ILongTermMemoryRepository {

    /**
     * 保存或更新长期记忆（Upsert 模式）。
     * <p>
     * 先通过唯一键 (userId, memoryType, memoryKey) 查询是否已存在：
     * <ul>
     *   <li>不存在 → INSERT 新记忆</li>
     *   <li>已存在 → UPDATE content/keywords/confidence 等字段，hit_count + 1</li>
     * </ul>
     *
     * @param memoryEntity 长期记忆实体
     */
    void saveOrUpdate(LongTermMemoryEntity memoryEntity);

    /**
     * 按用户 ID 查询最近的 N 条长期记忆（粗筛阶段）。
     * <p>
     * SQL 按 updated_at DESC, id DESC 排序取最近 N 条，
     * 真正的"搜索"在 LongTermMemoryService 中用关键词重叠打分完成（精排阶段）。
     *
     * @param userId 用户 ID
     * @param limit  最大返回条数（LongTermMemoryService 传入 120）
     * @return 长期记忆列表
     */
    List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit);

}
