package com.gode.infrastructure.dao;

import com.gode.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IChatMessageDao {

    /**
     * 插入一条对话消息
     *
     * @param po 消息持久化对象
     */
    void insert(ChatMessagePO po);

    /**
     * 按会话 ID 查询最近的消息（倒序，用于冷启动恢复和消息列表展示）
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（按 id DESC 排序）
     */
    List<ChatMessagePO> queryRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);

    List<ChatMessagePO> queryVisibleMessagePage(@Param("sessionId") String sessionId, @Param("beforeMessageId") Long beforeMessageId, @Param("limit") int limit);
    int deleteBySessionId(@Param("sessionId") String sessionId);

}
