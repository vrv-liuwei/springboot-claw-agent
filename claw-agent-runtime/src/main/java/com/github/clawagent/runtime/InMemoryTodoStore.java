package com.github.clawagent.runtime;

import com.github.clawagent.core.TodoItem;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.TodoStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 TodoStore，用于非 SQLite 场景的兜底。
 */
public class InMemoryTodoStore implements TodoStore, AgentDataCleaner {
    private final Map<String, TodoItem> items = new LinkedHashMap<>();

    @Override
    public synchronized void saveTodoItems(List<TodoItem> items) {
        for (TodoItem item : items) {
            this.items.put(item.id(), item);
        }
    }

    @Override
    public synchronized Optional<TodoItem> findTodoItem(String id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized List<TodoItem> listTodoItems(String sessionId, String taskId, int limit) {
        return items.values().stream()
                .filter(item -> sessionId == null || sessionId.isBlank() || sessionId.equals(item.sessionId()))
                .filter(item -> taskId == null || taskId.isBlank() || taskId.equals(item.taskId()))
                .sorted(Comparator.comparing(TodoItem::createdAt).thenComparing(TodoItem::itemOrder))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized TodoItem updateTodoStatus(String id, String status) {
        TodoItem old = findTodoItem(id).orElseThrow(() -> new IllegalArgumentException("Todo 不存在：" + id));
        TodoItem updated = new TodoItem(old.id(), old.sessionId(), old.taskId(), old.itemOrder(), old.title(),
                old.description(), status, old.metadata(), old.createdAt(), Instant.now());
        items.put(id, updated);
        return updated;
    }

    @Override
    public synchronized void clearAllAgentData() {
        // Todo 与会话/任务关联，清空历史时不能留下孤立计划项。
        items.clear();
    }
}
