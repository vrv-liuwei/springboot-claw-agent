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
}
