package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCall(String toolId, Map<String, String> arguments) {
    public ToolCall {
        arguments = new LinkedHashMap<>(arguments);
    }
}
