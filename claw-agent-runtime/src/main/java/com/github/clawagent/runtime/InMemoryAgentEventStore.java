package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.AgentEventStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存事件存储，供无数据库模式使用。
 */
public class InMemoryAgentEventStore implements AgentEventStore, AgentDataCleaner {
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void saveEvent(AgentEvent event) {
        events.add(event);
    }

    @Override
    public List<AgentEvent> findEventsBySession(String sessionId, int limit) {
        return events.stream()
                .filter(event -> sessionId.equals(event.sessionId()))
                .sorted(Comparator.comparing(AgentEvent::createdAt))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<AgentEvent> findEventsByTask(String taskId, int limit) {
        return events.stream()
                .filter(event -> taskId.equals(event.taskId()))
                .sorted(Comparator.comparing(AgentEvent::createdAt))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public List<AgentEvent> findEvents(Instant from, Instant to, String level, String type, String sessionId, String taskId, int limit) {
        return events.stream()
                .filter(event -> from == null || !event.createdAt().isBefore(from))
                .filter(event -> to == null || !event.createdAt().isAfter(to))
                .filter(event -> level == null || level.isBlank() || level.equalsIgnoreCase(event.level()))
                .filter(event -> type == null || type.isBlank() || event.type().contains(type))
                .filter(event -> sessionId == null || sessionId.isBlank() || sessionId.equals(event.sessionId()))
                .filter(event -> taskId == null || taskId.isBlank() || taskId.equals(event.taskId()))
                .sorted(Comparator.comparing(AgentEvent::createdAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public void clearAllAgentData() {
        // 事件日志属于审计历史，清空会话时一起删除。
        events.clear();
    }
}
