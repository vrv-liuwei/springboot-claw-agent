package com.github.clawagent.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP Client 抽象。
 * 不同传输方式只要实现这个接口，就能被统一映射到 AgentToolRegistry。
 */
public interface McpClient extends AutoCloseable {
    void initialize();

    List<McpToolDescriptor> listTools();

    List<McpResourceDescriptor> listResources();

    List<McpPromptDescriptor> listPrompts();

    McpResourceContent readResource(String uri);

    McpPromptContent getPrompt(String name, Map<String, Object> arguments);

    String callTool(String toolName, Map<String, Object> arguments);

    @Override
    void close();
}
