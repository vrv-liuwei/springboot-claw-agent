package com.github.clawagent.spi;

import com.github.clawagent.core.ToolDefinition;

import java.util.List;

/**
 * 支持原生 Tool Calling 的模型客户端。
 * OpenAI 兼容模型可通过 tools/tool_calls 协议直接返回工具调用。
 */
public interface ToolCallingModelClient extends ModelClient {
    ToolCallingResult chatWithTools(List<ChatMessage> messages, ChatOptions options, List<ToolDefinition> tools);
}
