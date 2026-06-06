package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentEvent 是会话级审计日志。
 * 它记录工具链路、LLM 请求响应、token 用量和运行错误，便于按 session/task 查询完整执行过程。
 */
public class AgentEvent {
    /** 事件 ID。 */
    private final String id;
    /** 事件所属会话 ID。 */
    private final String sessionId;
    /** 事件所属任务 ID。 */
    private final String taskId;
    /** 事件级别，例如 info、warn、error。 */
    private final String level;
    /** 事件类型，例如 llm.call、tool.call、task.failed。 */
    private final String type;
    /** 面向管理台展示的简短事件说明。 */
    private final String message;
    /** 事件结构化详情，保存轻量字符串键值。 */
    private final Map<String, String> details;
    /** 事件创建时间。 */
    private final Instant createdAt;

    /**
     * 创建当前时间的 AgentEvent。
     *
     * @param id 事件 ID。
     * @param sessionId 事件所属会话 ID。
     * @param taskId 事件所属任务 ID。
     * @param level 事件级别。
     * @param type 事件类型。
     * @param message 事件说明。
     * @param details 事件详情。
     */
    public AgentEvent(String id, String sessionId, String taskId, String level, String type, String message, Map<String, String> details) {
        this(id, sessionId, taskId, level, type, message, details, Instant.now());
    }

    /**
     * 创建可恢复历史时间的 AgentEvent。
     *
     * @param id 事件 ID。
     * @param sessionId 事件所属会话 ID。
     * @param taskId 事件所属任务 ID。
     * @param level 事件级别。
     * @param type 事件类型。
     * @param message 事件说明。
     * @param details 事件详情。
     * @param createdAt 事件创建时间。
     */
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
