package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool 元数据。
 * inputSchema 保留原始 JSON Schema 的 Map 形态，便于后续做表单和参数校验。
 */
public record McpToolDescriptor(
        /** 工具所属的 MCP Server id。 */
        String serverId,
        /** MCP Server 暴露出来的原始工具名。 */
        String name,
        /** 工具描述，用于 LLM 选择工具和管理台展示。 */
        String description,
        /** MCP tools/list 返回的 inputSchema，保留 JSON Schema 结构。 */
        Map<String, Object> inputSchema) {
    public McpToolDescriptor {
        // inputSchema 后续会被参数转换和表单渲染使用，因此这里保留字段顺序并隔离外部修改。
        inputSchema = inputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputSchema);
    }

    public String agentToolId() {
        return "mcp." + serverId + "." + name;
    }
}
