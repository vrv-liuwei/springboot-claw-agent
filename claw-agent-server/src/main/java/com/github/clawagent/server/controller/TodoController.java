package com.github.clawagent.server.controller;

import com.github.clawagent.core.TodoItem;
import com.github.clawagent.spi.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Todo 列表和状态维护接口。
 */
@RestController
@RequestMapping("/api/v1")
public class TodoController {
    private static final Logger log = LoggerFactory.getLogger(TodoController.class);

    private final TodoStore todoStore;

    public TodoController(@Qualifier("todoStore") TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    /**
     * 按会话或任务查询 Todo；列表渲染和任务恢复都会使用这份状态。
     */
    @GetMapping("/todos")
    public List<TodoItem> todos(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.trace("todo list requested sessionId={} taskId={} limit={}", sessionId, taskId, limit);
        return todoStore.listTodoItems(sessionId, taskId, limit);
    }

    /**
     * 更新单条 Todo 状态，供管理台手工修正和工具链同步使用。
     */
    @PostMapping("/todos/{todoId}/status")
    public TodoItem updateTodoStatus(
            @PathVariable("todoId") String todoId,
            @RequestBody Map<String, String> body) {
        String status = body == null ? "" : body.getOrDefault("status", "");
        log.info("todo status update requested id={} status={}", todoId, status);
        return todoStore.updateTodoStatus(todoId, status);
    }
}
