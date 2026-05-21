package com.github.clawagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析标准 mcpServers JSON。
 * 目标是让用户直接粘贴 Cherry Studio / Claude Desktop 配置，而不是理解 clawagent 内部字段。
 */
public class McpConfigImporter {
    /** JSON 解析器，用于读取标准 mcpServers 配置和历史数组配置。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<McpServerConfig> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode serversNode = root.has("mcpServers") ? root.path("mcpServers") : root;
            if (serversNode.isArray()) {
                return readLegacyArray(serversNode);
            }
            if (!serversNode.isObject()) {
                throw new IllegalArgumentException("未找到 mcpServers 对象");
            }
            List<McpServerConfig> configs = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                configs.add(readServer(field.getKey(), field.getValue()));
            }
            return configs;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 MCP JSON 失败：" + e.getMessage(), e);
        }
    }

    private List<McpServerConfig> readLegacyArray(JsonNode serversNode) {
        List<McpServerConfig> configs = new ArrayList<>();
        for (JsonNode node : serversNode) {
            String id = firstText(node, "id", "name");
            configs.add(readServer(id, node));
        }
        return configs;
    }

    private McpServerConfig readServer(String id, JsonNode node) {
        String command = text(node, "command");
        String url = firstText(node, "url", "endpoint");
        McpTransport explicitTransport = McpTransport.fromConfigValue(firstText(node, "type", "transportType", "transport"));
        McpTransport transport = explicitTransport == null
                ? command == null || command.isBlank() ? inferHttpTransport(url) : McpTransport.STDIO
                : explicitTransport;
        return new McpServerConfig(
                id,
                firstText(node, "name", "title"),
                transport,
                url,
                command,
                readStringArray(node.path("args")),
                readStringMap(node.path("env")),
                readStringMap(node.path("headers")),
                firstText(node, "cwd", "workingDirectory"),
                node.path("timeout").asInt(60),
                readStringArray(node.path("autoApprove")),
                !node.path("disabled").asBoolean(false));
    }

    private McpTransport inferHttpTransport(String url) {
        if (url == null || url.isBlank()) {
            return McpTransport.STDIO;
        }
        String lower = url.toLowerCase();
        if (lower.contains("sse")) {
            return McpTransport.SSE;
        }
        return McpTransport.HTTP;
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }

    private Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!node.isObject()) {
            return values;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), field.getValue().asText());
        }
        return values;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String name) {
        return node.has(name) && !node.path(name).isNull() ? node.path(name).asText() : null;
    }
}
