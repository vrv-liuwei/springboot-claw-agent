package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Prompt 获取请求。
 */
public record McpPromptGetRequest(
        /** Prompt 参数。 */
        Map<String, Object> arguments) {
    public McpPromptGetRequest {
        arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }
}
