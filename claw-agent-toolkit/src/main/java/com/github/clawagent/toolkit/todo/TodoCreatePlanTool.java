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
import java.util.LinkedHashMap;
import java.util.List;
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
