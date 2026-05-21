package com.github.clawagent.spi;

import com.github.clawagent.core.ToolCall;

import java.util.List;

/**
 * 模型原生 Tool Calling 返回值。
 */
public record ToolCallingResult(
        /** 模型自然语言内容；有些模型在 tool_calls 场景下可能为空。 */
        String content,
        /** 模型返回的工具调用列表。 */
        List<ToolCall> toolCalls) {
    public ToolCallingResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
