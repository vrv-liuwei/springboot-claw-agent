package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentMessage 是会话内的消息事实。
 * M2 先保存 user / assistant 消息，后续会扩展 tool、system、summary 和 token 统计。
 */
public class AgentMessage {
    private final String id;
    private final String sessionId;
    private final String taskId;
    private final String role;
    private final String content;
    private final Map<String, String> metadata;
    private final Instant createdAt;

    public AgentMessage(String id, String sessionId, String taskId, String role, String content, Map<String, String> metadata) {
        this.id = id;
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.role = role;
        this.content = content;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        this.createdAt = Instant.now();
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String sessionId() { return sessionId; }
    public String getSessionId() { return sessionId; }
    public String taskId() { return taskId; }
    public String getTaskId() { return taskId; }
    public String role() { return role; }
    public String getRole() { return role; }
    public String content() { return content; }
    public String getContent() { return content; }
    public Map<String, String> metadata() { return metadata; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
}
