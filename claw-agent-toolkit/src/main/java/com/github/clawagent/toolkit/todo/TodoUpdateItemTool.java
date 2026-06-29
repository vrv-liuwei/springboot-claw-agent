package com.github.clawagent.toolkit.todo;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.TodoStore;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 更新 Todo 状态工具。
 */
public class TodoUpdateItemTool implements AgentTool {
    private final TodoStore todoStore;

    public TodoUpdateItemTool(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("id", ToolDefinition.stringProperty("Todo item ID。已知 UUID 时优先使用。"));
        schema.put("order", ToolDefinition.integerProperty("当前会话 Todo 步骤序号。用户说执行第 N 步但不知道 id 时使用。"));
        schema.put("status", Map.of(
                "type", "string",
                "description", "目标状态",
                "enum", List.of("pending", "running", "completed", "failed")));
        return ToolDefinition.low(
                "builtin.todo.update_item",
                "Update Todo Item",
                "更新 Todo item 状态。状态支持 pending/running/completed/failed。可以用 id 更新，也可以用当前会话的 order 更新。",
                ToolDefinition.objectSchema(schema, false, List.of("status")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            String status = required(call, "status").toLowerCase(Locale.ROOT);
            if (!List.of("pending", "running", "completed", "failed").contains(status)) {
                throw new IllegalArgumentException("不支持的 Todo 状态：" + status);
            }
            TodoItem item = todoStore.updateTodoStatus(resolveTodoId(call, context), status);
            return ToolResult.success(item.id() + " -> " + item.status());
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String resolveTodoId(ToolCall call, AgentContext context) {
        String id = call.arguments().get("id");
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        String orderText = call.arguments().get("order");
        if (orderText == null || orderText.isBlank()) {
            throw new IllegalArgumentException("缺少参数：id 或 order");
        }
        int order = Integer.parseInt(orderText.trim());
        List<TodoItem> items = todoStore.listTodoItems(context.task().sessionId(), "", 200);
        String activePlanTaskId = activePlanTaskId(items, context.task().id());
        // 用户常说“执行第二步”，模型不一定知道 UUID；优先定位当前 task 创建的计划，避免误用同会话里旧任务的 order。
        return items.stream()
                .filter(item -> activePlanTaskId.equals(item.taskId()))
                .filter(item -> item.itemOrder() == order)
                .findFirst()
                .map(TodoItem::id)
                .orElseThrow(() -> new IllegalArgumentException("当前会话未完成计划不存在第 " + order + " 个 Todo"));
    }

    private String activePlanTaskId(List<TodoItem> items, String currentTaskId) {
        boolean currentTaskHasTodos = items.stream()
                .anyMatch(item -> currentTaskId.equals(item.taskId()));
        if (currentTaskHasTodos) {
            return currentTaskId;
        }
        return items.stream()
                .filter(this::isIncomplete)
                .max(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
    }

    private boolean isIncomplete(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase(Locale.ROOT);
        // failed Todo 仍属于可恢复计划；继续任务时模型需要能用 order 把它重新置为 running/completed。
        return "pending".equals(status) || "running".equals(status) || "failed".equals(status);
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }
}
