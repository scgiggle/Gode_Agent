package com.gode.infrastructure.dao;

import com.gode.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IChatSessionDao {

    /**
     * 插入会话元数据记录
     *
     * @param po 会话持久化对象
     */
    void insert(ChatSessionPO po);

    /**
     * 更新会话消息计数（message_count = message_count + 1，保证并发安全）
     *
     * @param sessionId 会话 ID
     */
    void updateMessageCount(@Param("sessionId") String sessionId);

    /**
     * 查询用户在某 Agent 下的会话列表（用于前端历史会话列表展示）
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数
     * @return 会话列表
     */
    List<ChatSessionPO> querySessionList(@Param("agentId") String agentId, @Param("userId") String userId, @Param("limit") int limit);

    ChatSessionPO queryOwnedSession(@Param("agentId") String agentId, @Param("userId") String userId, @Param("sessionId") String sessionId);
    ChatSessionPO lockOwnedSession(@Param("agentId") String agentId, @Param("userId") String userId, @Param("sessionId") String sessionId);
    List<ChatSessionPO> querySessionPage(@Param("agentId") String agentId, @Param("userId") String userId, @Param("offset") int offset, @Param("limit") int limit);
    int updateDefaultTitle(@Param("sessionId") String sessionId, @Param("title") String title);
    int deleteOwnedSession(@Param("agentId") String agentId, @Param("userId") String userId, @Param("sessionId") String sessionId);

}
