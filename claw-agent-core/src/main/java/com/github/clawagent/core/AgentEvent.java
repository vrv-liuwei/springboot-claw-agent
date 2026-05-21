package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentEvent 是会话级审计日志。
 * 它记录工具链路、LLM 请求响应、token 用量和运行错误，便于按 session/task 查询完整执行过程。
 */
public class AgentEvent {
    private final String id;
    private final String sessionId;
    private final String taskId;
    private final String level;
    private final String type;
    private final String message;
    private final Map<String, String> details;
    private final Instant createdAt;

    public AgentEvent(String id, String sessionId, String taskId, String level, String type, String message, Map<String, String> details) {
        this(id, sessionId, taskId, level, type, message, details, Instant.now());
    }

    public AgentEvent(String id, String sessionId, String taskId, String level, String type, String message, Map<String, String> details, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.level = level;
        this.type = type;
        this.message = message;
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String sessionId() { return sessionId; }
    public String getSessionId() { return sessionId; }
    public String taskId() { return taskId; }
    public String getTaskId() { return taskId; }
    public String level() { return level; }
    public String getLevel() { return level; }
    public String type() { return type; }
    public String getType() { return type; }
    public String message() { return message; }
    public String getMessage() { return message; }
    public Map<String, String> details() { return details; }
    public Map<String, String> getDetails() { return details; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
}
