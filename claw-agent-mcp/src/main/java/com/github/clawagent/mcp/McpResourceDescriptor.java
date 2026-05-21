package com.github.clawagent.mcp;

/**
 * MCP Resource 描述。
 * Resource 是 MCP Server 暴露的可读上下文，例如文件、文档、数据库片段或外部系统对象。
 */
public record McpResourceDescriptor(
        /** 来源 MCP Server 的 id。 */
        String serverId,
        /** Resource 唯一 URI，由 MCP Server 定义。 */
        String uri,
        /** Resource 展示名称。 */
        String name,
        /** Resource 说明。 */
        String description,
        /** Resource MIME 类型，例如 text/plain、application/json。 */
        String mimeType) {
}
