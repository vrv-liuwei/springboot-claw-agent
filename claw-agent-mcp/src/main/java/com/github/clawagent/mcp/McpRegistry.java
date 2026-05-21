package com.github.clawagent.mcp;

import java.util.List;
import java.util.Optional;

/**
 * MCP 注册表接口。
 * Runtime 后续会从这里发现 MCP tools/resources/prompts，并映射为 AgentTool。
 */
public interface McpRegistry {
    McpServerRegistration test(McpServerConfig config);

    McpServerRegistration register(McpServerConfig config);

    List<McpServerRegistration> importServers(String json);

    McpServerRegistration connect(String serverId);

    List<McpServerRegistration> connectAll();

    McpServerRegistration disconnect(String serverId);

    List<McpToolDescriptor> refreshTools(String serverId);

    List<McpToolDescriptor> listTools(String serverId);

    List<McpResourceDescriptor> refreshResources(String serverId);

    List<McpResourceDescriptor> listResources(String serverId);

    List<McpPromptDescriptor> refreshPrompts(String serverId);

    List<McpPromptDescriptor> listPrompts(String serverId);

    McpResourceContent readResource(String serverId, String uri);

    McpPromptContent getPrompt(String serverId, String name, java.util.Map<String, Object> arguments);

    Optional<McpServerRegistration> find(String serverId);

    List<McpServerRegistration> list();

    void disable(String serverId);
}
