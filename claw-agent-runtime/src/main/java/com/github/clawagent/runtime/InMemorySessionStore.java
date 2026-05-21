package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentSession;
import com.github.clawagent.spi.SessionStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存会话存储，用于测试或未配置持久化时的兜底模式。
 */
public class InMemorySessionStore implements SessionStore {
    private final Map<String, AgentSession> sessions = new LinkedHashMap<>();

    @Override
    public synchronized void saveSession(AgentSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public synchronized void updateSession(AgentSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public synchronized Optional<AgentSession> findSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public synchronized List<AgentSession> listSessions(int limit) {
        return sessions.values().stream()
                .sorted(Comparator.comparing(AgentSession::lastActiveAt).reversed())
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
