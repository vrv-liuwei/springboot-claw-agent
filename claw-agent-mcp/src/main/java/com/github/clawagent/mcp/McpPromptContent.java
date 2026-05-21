package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Prompt 获取结果。
 */
public record McpPromptContent(
        /** 来源 MCP Server 的 id。 */
        String serverId,
        /** Prompt 名称。 */
        String name,
        /** MCP prompts/get 返回的原始内容结构。 */
        Map<String, Object> content) {
    public McpPromptContent {
        content = content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content);
    }
}
