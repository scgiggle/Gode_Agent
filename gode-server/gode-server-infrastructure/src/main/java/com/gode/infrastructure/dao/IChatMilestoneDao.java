package com.gode.infrastructure.dao;

import com.gode.infrastructure.dao.po.ChatMilestonePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IChatMilestoneDao {

    /**
     * 插入一条里程碑事件记录
     *
     * @param po 里程碑持久化对象
     */
    void insert(ChatMilestonePO po);

    /**
     * 按会话 ID 查询最近的里程碑事件（倒序）
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 里程碑列表（按 id DESC 排序）
     */
    List<ChatMilestonePO> queryRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);

    int deleteBySessionId(@Param("sessionId") String sessionId);

}
