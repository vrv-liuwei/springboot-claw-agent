package com.github.clawagent.spi;

import com.github.clawagent.core.AgentSession;

import java.util.List;
import java.util.Optional;

/**
 * SessionStore 是会话事实源接口。
 * 单机默认使用 SQLite，后续 MySQL/PostgreSQL/分布式同步会实现同一组语义。
 */
public interface SessionStore {
    void saveSession(AgentSession session);

    void updateSession(AgentSession session);

    Optional<AgentSession> findSession(String sessionId);

    List<AgentSession> listSessions(int limit);

    default boolean deleteSession(String sessionId) {
        return false;
    }
}
