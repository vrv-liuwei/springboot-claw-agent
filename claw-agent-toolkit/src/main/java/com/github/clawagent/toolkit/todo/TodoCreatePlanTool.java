package com.github.clawagent.toolkit.todo;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.TodoStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 创建 Todo 计划工具。
 * Planner 可先调用它把复杂任务拆成多个可追踪步骤，后续再逐步执行。
 */
public class TodoCreatePlanTool implements AgentTool {
    private final TodoStore todoStore;

    public TodoCreatePlanTool(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("items", ToolDefinition.stringProperty("Todo JSON 数组。元素可以是字符串，或 {\"title\":\"...\",\"description\":\"...\"}"));
        return ToolDefinition.low(
                "builtin.todo.create_plan",
                "Create Todo Plan",
                "把复杂任务拆解为 Todo 计划并持久化。复杂、多步骤、需要多次工具调用的任务应优先使用。",
                ToolDefinition.objectSchema(schema, false, List.of("items")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            List<TodoItem> existing = todoStore.listTodoItems(context.task().sessionId(), "", 200);
            List<TodoItem> incompleteExisting = activeIncompleteItems(existing);
            if (isContinuationRequest(context.task().input()) && !incompleteExisting.isEmpty()) {
                // 续跑同一会话时沿用原计划，避免模型反复 create_plan 导致 Todo 计数和 order 定位漂移。
                return ToolResult.success(formatExisting(incompleteExisting));
            }
            JSONArray rawItems = JSONUtil.parseArray(required(call, "items"));
            List<TodoItem> items = new ArrayList<>();
            for (int i = 0; i < rawItems.size(); i++) {
                Object raw = rawItems.get(i);
                String title;
                String description = "";
                if (raw instanceof JSONObject object) {
                    title = firstNonBlank(object.getStr("title", ""), object.getStr("text", ""), object.getStr("description", ""), object.getStr("id", ""));
                    description = firstNonBlank(object.getStr("description", ""), object.getStr("text", ""));
                } else {
                    title = raw == null ? "" : raw.toString();
                }
                if (title.isBlank()) {
                    continue;
                }
                // Todo 和当前 session/task 绑定，便于页面按会话查询和后续恢复执行链。
                items.add(new TodoItem(
                        UUID.randomUUID().toString(),
                        context.task().sessionId(),
                        context.task().id(),
                        i + 1,
                        title,
                        description,
                        "pending",
                        Map.of(),
                        null,
                        null));
            }
            todoStore.saveTodoItems(items);
            return ToolResult.success(format(items));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String format(List<TodoItem> items) {
        StringBuilder builder = new StringBuilder();
        builder.append("created: ").append(items.size()).append("\n");
        for (TodoItem item : items) {
            builder.append("- [").append(item.status()).append("] ")
                    .append(item.id()).append(" ")
                    .append(item.itemOrder()).append(". ")
                    .append(item.title()).append("\n");
        }
        return builder.toString();
    }

    private String formatExisting(List<TodoItem> items) {
        StringBuilder builder = new StringBuilder();
        builder.append("existingPlan: true\n");
        builder.append("remaining: ").append(items.size()).append("\n");
        for (TodoItem item : items) {
            builder.append("- [").append(item.status()).append("] ")
                    .append(item.id()).append(" ")
                    .append(item.itemOrder()).append(". ")
                    .append(item.title()).append("\n");
        }
        builder.append("继续任务时不要重新创建 Todo 计划，请使用这些已有 Todo 的 id 或 order 更新状态。");
        return builder.toString();
    }

    private List<TodoItem> activeIncompleteItems(List<TodoItem> items) {
        String taskId = activePlanTaskId(items);
        if (taskId.isBlank()) {
            return List.of();
        }
        return items.stream()
                .filter(item -> taskId.equals(item.taskId()))
                .filter(this::isIncomplete)
                .sorted(Comparator.comparingInt(TodoItem::itemOrder))
                .toList();
    }

    private String activePlanTaskId(List<TodoItem> items) {
        return items.stream()
                .filter(this::isIncomplete)
                .min(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
    }

    private boolean isIncomplete(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase(Locale.ROOT);
        return "pending".equals(status) || "running".equals(status);
    }

    private boolean isContinuationRequest(String input) {
        String value = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return value.contains("继续") || value.contains("接着") || value.contains("续跑") || value.contains("continue");
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
