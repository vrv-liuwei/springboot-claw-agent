package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;

import java.util.List;

/**
 * 会话消息存储接口。
 * 它和 SessionStore 分开，便于后续把消息迁移到更适合检索或归档的存储。
 */
public interface SessionMessageStore {
    void saveMessage(AgentMessage message);

    List<AgentMessage> findMessages(String sessionId, int limit);

    /**
     * 查询指定时间之前的会话消息，用于聊天窗口向上滚动加载更早历史。
     *
     * @param sessionId 会话 ID
     * @param beforeCreatedAt 游标时间，只返回早于该时间的消息
     * @param limit 最大返回条数
     * @return 按创建时间正序排列的消息
     */
    List<AgentMessage> findMessagesBefore(String sessionId, java.time.Instant beforeCreatedAt, int limit);

    List<AgentMessage> findMessagesByTask(String taskId, int limit);
}
