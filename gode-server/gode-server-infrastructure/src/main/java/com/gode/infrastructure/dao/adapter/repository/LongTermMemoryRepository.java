package com.gode.infrastructure.dao.adapter.repository;

import com.gode.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.gode.domain.agent.model.entity.LongTermMemoryEntity;
import com.gode.infrastructure.dao.ILongTermMemoryDao;
import com.gode.infrastructure.dao.po.LongTermMemoryPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    @Override
    public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        LongTermMemoryPO exist = longTermMemoryDao.queryByIdentity(
                memoryEntity.getUserId(),
                memoryEntity.getMemoryType(),
                memoryEntity.getMemoryKey()
        );

        if (exist == null) {
            // 不存在 → 新建记忆
            longTermMemoryDao.insert(toPO(memoryEntity));
            return;
        }

        // 已存在 → 更新内容并 hit_count + 1
        exist.setSessionId(memoryEntity.getSessionId());
        exist.setContent(memoryEntity.getContent());
        exist.setKeywords(memoryEntity.getKeywords());
        exist.setSourceRole(memoryEntity.getSourceRole());
        exist.setConfidence(memoryEntity.getConfidence());
        exist.setHitCount((exist.getHitCount() == null ? 0 : exist.getHitCount()) + 1);
        longTermMemoryDao.update(exist);
    }

    @Override
    public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
        List<LongTermMemoryPO> list = longTermMemoryDao.queryRecentByUserId(userId, limit);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(this::toEntity).collect(Collectors.toList());
    }


    // ==================== PO ↔ Entity 转换 ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        return LongTermMemoryPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .sessionId(entity.getSessionId())
                .memoryType(entity.getMemoryType())
                .memoryKey(entity.getMemoryKey())
                .content(entity.getContent())
                .keywords(entity.getKeywords())
                .sourceRole(entity.getSourceRole())
                .confidence(entity.getConfidence())
                .hitCount(entity.getHitCount())
                .build();
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence())
                .hitCount(po.getHitCount())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

}
