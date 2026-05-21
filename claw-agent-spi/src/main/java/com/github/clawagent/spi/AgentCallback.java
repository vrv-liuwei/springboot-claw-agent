package com.github.clawagent.spi;

public interface AgentCallback {
    void onEvent(String eventType, String taskId, String message);
}
