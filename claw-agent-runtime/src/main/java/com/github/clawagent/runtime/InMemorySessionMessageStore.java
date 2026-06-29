package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.SessionMessageStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存会话消息存储，用于测试或非持久化模式。
 */
public class InMemorySessionMessageStore implements SessionMessageStore, AgentDataCleaner {
    private final Map<String, List<AgentMessage>> messages = new LinkedHashMap<>();

    @Override
    public synchronized void saveMessage(AgentMessage message) {
        messages.computeIfAbsent(message.sessionId(), key -> new ArrayList<>()).add(message);
    }

    @Override
    public synchronized List<AgentMessage> findMessages(String sessionId, int limit) {
        return messages.getOrDefault(sessionId, List.of()).stream()
                // 内存实现和 SQLite 保持一致：先取最近 N 条，再按时间正序返回给聊天窗口。
                .sorted(Comparator.comparing(AgentMessage::createdAt).reversed())
                .limit(Math.max(1, limit))
                .sorted(Comparator.comparing(AgentMessage::createdAt))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized List<AgentMessage> findMessagesBefore(String sessionId, java.time.Instant beforeCreatedAt, int limit) {
        return messages.getOrDefault(sessionId, List.of()).stream()
                // before 游标用于向上滚动加载旧消息，只返回游标之前最近的一页。
                .filter(message -> message.createdAt().isBefore(beforeCreatedAt))
                .sorted(Comparator.comparing(AgentMessage::createdAt).reversed())
                .limit(Math.max(1, limit))
                .sorted(Comparator.comparing(AgentMessage::createdAt))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized List<AgentMessage> findMessagesByTask(String taskId, int limit) {
        return messages.values().stream()
                .flatMap(List::stream)
                // task 详情弹窗只需要当前任务下的用户消息和助手回复，不能把整个会话消息混进来。
                .filter(message -> message.taskId().equals(taskId))
                .sorted(Comparator.comparing(AgentMessage::createdAt))
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized void clearAllAgentData() {
        // 清空所有会话消息，避免历史对话继续参与上下文。
        messages.clear();
    }
}
