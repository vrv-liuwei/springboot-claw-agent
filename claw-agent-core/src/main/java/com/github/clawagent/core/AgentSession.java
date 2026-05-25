package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentSession 表示一段可恢复的用户会话。
 * M2 从会话事实源开始建设，后续模型消息、摘要、长期记忆提升都会挂到 sessionId。
 */
public class AgentSession {
    private final String id;
    private final String title;
    private final String channelId;
    private final String userId;
    private final Map<String, String> metadata;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastActiveAt;
    private String summary;

    public AgentSession(String id, String title, String channelId, String userId, Map<String, String> metadata) {
        this(id, title, channelId, userId, metadata, Instant.now(), null, null, null);
    }

    public AgentSession(String id, String title, String channelId, String userId, Map<String, String> metadata,
                        Instant createdAt, Instant updatedAt, Instant lastActiveAt, String summary) {
        this.id = id;
        this.title = title;
        this.channelId = channelId;
        this.userId = userId;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        // 持久化恢复时必须使用数据库里的原始时间，不能在查询时重置为当前时间。
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
        this.lastActiveAt = lastActiveAt == null ? this.updatedAt : lastActiveAt;
        this.summary = summary;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public String channelId() { return channelId; }
    public String getChannelId() { return channelId; }
    public String userId() { return userId; }
    public String getUserId() { return userId; }
    public Map<String, String> metadata() { return metadata; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant lastActiveAt() { return lastActiveAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public String summary() { return summary; }
    public String getSummary() { return summary; }

    public void touch() {
        this.lastActiveAt = Instant.now();
        this.updatedAt = this.lastActiveAt;
    }

    public void updateSummary(String summary) {
        this.summary = summary;
        this.updatedAt = Instant.now();
    }
}
