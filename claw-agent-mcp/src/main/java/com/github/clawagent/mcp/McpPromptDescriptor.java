package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Prompt 描述。
 * Prompt 是 MCP Server 提供的可复用提示词模板，后续可挂到管理台供用户查看和调用。
 */
public record McpPromptDescriptor(
        /** 来源 MCP Server 的 id。 */
        String serverId,
        /** Prompt 名称，调用时作为 MCP prompts/get 的 name。 */
        String name,
        /** Prompt 说明。 */
        String description,
        /** Prompt 参数 schema 或参数列表原始结构。 */
        Map<String, Object> arguments) {
    public McpPromptDescriptor {
        // 防御性拷贝，避免外部修改影响 registry 缓存。
        arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }
}
