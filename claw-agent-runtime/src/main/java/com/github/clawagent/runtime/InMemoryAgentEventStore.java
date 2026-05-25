package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.AgentEventStore;

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
    public void clearAllAgentData() {
        // 事件日志属于审计历史，清空会话时一起删除。
        events.clear();
    }
}
