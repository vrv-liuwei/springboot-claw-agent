package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用请求。
 *
 * @param toolId 要调用的工具 ID。
 * @param arguments 工具参数，统一按字符串键值传递给工具实现。
 */
public record ToolCall(String toolId, Map<String, String> arguments) {
    public ToolCall {
        arguments = new LinkedHashMap<>(arguments);
    }
}
