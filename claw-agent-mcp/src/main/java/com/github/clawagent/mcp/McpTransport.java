package com.github.clawagent.mcp;

/**
 * MCP 传输类型。
 * M3 先定义通用模型，后续分别实现 stdio、HTTP/SSE、streamable HTTP 连接器。
 */
public enum McpTransport {
    STDIO,
    HTTP,
    SSE,
    STREAMABLE_HTTP;

    public static McpTransport fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .replace("-", "")
                .replace("_", "")
                .toLowerCase();
        return switch (normalized) {
            case "stdio" -> STDIO;
            case "sse" -> SSE;
            case "streamablehttp" -> STREAMABLE_HTTP;
            case "http" -> HTTP;
            default -> throw new IllegalArgumentException("不支持的 MCP transport/type：" + value);
        };
    }
}
