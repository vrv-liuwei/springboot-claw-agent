package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Resource 读取结果。
 */
public record McpResourceContent(
        /** 来源 MCP Server 的 id。 */
        String serverId,
        /** Resource URI。 */
        String uri,
        /** MCP resources/read 返回的原始内容结构。 */
        Map<String, Object> content) {
    public McpResourceContent {
        content = content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content);
    }
}
