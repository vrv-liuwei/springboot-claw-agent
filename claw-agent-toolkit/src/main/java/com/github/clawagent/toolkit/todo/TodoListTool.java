package com.github.clawagent.toolkit.todo;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.TodoStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 Todo 列表工具。
 */
public class TodoListTool implements AgentTool {
    private final TodoStore todoStore;

    public TodoListTool(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sessionId", ToolDefinition.stringProperty("可选 sessionId，默认当前会话"));
        schema.put("taskId", ToolDefinition.stringProperty("可选 taskId"));
        schema.put("limit", ToolDefinition.integerProperty("可选返回条数，默认 50"));
        return ToolDefinition.low(
                "builtin.todo.list",
                "List Todo Items",
                "查询 Todo 计划列表，可按 sessionId/taskId 过滤。",
                ToolDefinition.objectSchema(schema, false, List.of()));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String sessionId = call.arguments().getOrDefault("sessionId", context.task().sessionId());
        String taskId = call.arguments().getOrDefault("taskId", "");
        int limit = intArg(call, "limit", 50);
        List<TodoItem> items = todoStore.listTodoItems(sessionId, taskId, limit);
        StringBuilder builder = new StringBuilder();
        builder.append("count: ").append(items.size()).append("\n");
        for (TodoItem item : items) {
            builder.append("- [").append(item.status()).append("] ")
                    .append(item.id()).append(" ")
                    .append(item.itemOrder()).append(". ")
                    .append(item.title()).append("\n");
        }
        return ToolResult.success(builder.toString());
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }
}
