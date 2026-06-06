package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentMessage 是会话内的消息事实。
 * M2 先保存 user / assistant 消息，后续会扩展 tool、system、summary 和 token 统计。
 */
public class AgentMessage {
    /** 消息 ID。 */
    private final String id;
    /** 消息所属会话 ID。 */
    private final String sessionId;
    /** 消息关联任务 ID。 */
    private final String taskId;
    /** 消息角色，例如 user、assistant、system、tool。 */
    private final String role;
    /** 消息正文内容。 */
    private final String content;
    /** 消息轻量扩展元信息。 */
    private final Map<String, String> metadata;
    /** 消息创建时间。 */
    private final Instant createdAt;

    /**
     * 创建会话消息。
     *
     * @param id 消息 ID。
     * @param sessionId 消息所属会话 ID。
     * @param taskId 消息关联任务 ID。
     * @param role 消息角色。
     * @param content 消息正文内容。
     * @param metadata 消息轻量扩展元信息。
     */
    public AgentMessage(String id, String sessionId, String taskId, String role, String content, Map<String, String> metadata) {
        this(id, sessionId, taskId, role, content, metadata, Instant.now());
    }

    /**
     * 从持久化存储恢复会话消息。
     *
     * @param id 消息 ID。
     * @param sessionId 消息所属会话 ID。
     * @param taskId 消息关联任务 ID。
     * @param role 消息角色。
     * @param content 消息正文内容。
     * @param metadata 消息轻量扩展元信息。
     * @param createdAt 消息原始创建时间。
     */
    public AgentMessage(String id, String sessionId, String taskId, String role, String content, Map<String, String> metadata, Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.role = role;
        this.content = content;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
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
