package com.github.clawagent.spi;

import com.github.clawagent.core.TodoItem;

import java.util.List;
import java.util.Optional;

/**
 * TodoStore 保存复杂任务拆解后的待办项。
 * 默认由 SQLite 实现，后续分布式模式可替换为数据库或队列驱动实现。
 */
public interface TodoStore {
    void saveTodoItems(List<TodoItem> items);

    Optional<TodoItem> findTodoItem(String id);

    List<TodoItem> listTodoItems(String sessionId, String taskId, int limit);

    TodoItem updateTodoStatus(String id, String status);
}
