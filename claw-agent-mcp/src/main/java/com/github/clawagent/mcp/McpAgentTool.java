package com.github.clawagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 MCP tool 适配成 ClawAgent 标准工具。
 * Runtime 不需要知道工具来自本地、Skill 还是 MCP。
 */
public class McpAgentTool implements AgentTool {
    /** JSON 工具，用于把字符串参数恢复成 MCP inputSchema 要求的复杂类型。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** MCP tools/list 返回的工具描述。 */
    private final McpToolDescriptor descriptor;
    /** 已连接的 MCP Client，用于真正发起 tools/call。 */
    private final McpClient client;

    public McpAgentTool(McpToolDescriptor descriptor, McpClient client) {
        this.descriptor = descriptor;
        this.client = client;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.low(descriptor.agentToolId(), descriptor.name(), descriptor.description(), descriptor.inputSchema());
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            return ToolResult.success(client.callTool(descriptor.name(), coerceArguments(call.arguments())));
        } catch (RuntimeException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * 当前 ClawAgent 的 ToolCall 参数仍是字符串 Map。
     * MCP Server 会按 inputSchema 做类型校验，所以这里必须先把 number/boolean/array/object 转回 JSON 类型。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceArguments(Map<String, String> rawArguments) {
        Map<String, Object> coerced = new LinkedHashMap<>();
        Object properties = descriptor.inputSchema().get("properties");
        Map<String, Object> propertySchemas = properties instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        rawArguments.forEach((name, value) -> coerced.put(name, coerceValue(value, propertySchemas.get(name))));
        return coerced;
    }

    @SuppressWarnings("unchecked")
    private Object coerceValue(String value, Object schema) {
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return value;
        }
        Object type = schemaMap.get("type");
        if ("integer".equals(type)) {
            return Integer.parseInt(value);
        }
        if ("number".equals(type)) {
            return Double.parseDouble(value);
        }
        if ("boolean".equals(type)) {
            return Boolean.parseBoolean(value);
        }
        if ("array".equals(type) || "object".equals(type)) {
            return readJsonValue(value);
        }
        return value;
    }

    private Object readJsonValue(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            try {
                return objectMapper.readValue(value, new TypeReference<java.util.List<Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("MCP 参数需要 JSON 数组或对象：" + value, e);
            }
        }
    }
}
