package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ToolDefinition 是工具暴露给 Planner 和管理台的元数据。
 * riskLevel 用字符串保留扩展空间，当前约定 low / medium / high。
 *
 * @param id 工具唯一 ID。
 * @param name 工具展示名称。
 * @param description 工具能力描述，供 Planner 和管理台展示。
 * @param riskLevel 工具风险等级，当前约定 low、medium、high。
 * @param inputSchema 工具输入 JSON Schema。
 */
public record ToolDefinition(String id, String name, String description, String riskLevel, Map<String, Object> inputSchema) {
    public ToolDefinition(String id, String name, String description, String riskLevel) {
        this(id, name, description, riskLevel, objectSchema(Map.of(), true, java.util.List.of()));
    }

    public ToolDefinition {
        inputSchema = inputSchema == null ? objectSchema(Map.of(), true, java.util.List.of()) : new LinkedHashMap<>(inputSchema);
    }

    public static ToolDefinition low(String id, String name, String description) {
        return new ToolDefinition(id, name, description, "low");
    }

    public static ToolDefinition low(String id, String name, String description, Map<String, Object> inputSchema) {
        return new ToolDefinition(id, name, description, "low", inputSchema);
    }

    public static ToolDefinition high(String id, String name, String description, Map<String, Object> inputSchema) {
        return new ToolDefinition(id, name, description, "high", inputSchema);
    }

    public static Map<String, Object> objectSchema(Map<String, Object> properties, boolean additionalProperties, java.util.List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties));
        schema.put("additionalProperties", additionalProperties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", java.util.List.copyOf(required));
        }
        return schema;
    }

    public static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
