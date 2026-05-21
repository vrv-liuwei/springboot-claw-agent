package com.github.clawagent.mcp;

/**
 * MCP JSON 导入请求。
 * 支持 Cherry Studio、Claude Desktop 常见的 {"mcpServers": {...}} 格式。
 */
public record McpImportRequest(
        /** 用户从 Cherry Studio、Claude Desktop、Cursor 等客户端复制的 mcpServers JSON。 */
        String json) {
}
