package com.github.clawagent.spi;

import java.util.Map;

public interface AgentCallback {
    void onEvent(String eventType, String taskId, String message);

    default void onEvent(String eventType, String taskId, String message, Map<String, String> details) {
        onEvent(eventType, taskId, message);
    }
}
