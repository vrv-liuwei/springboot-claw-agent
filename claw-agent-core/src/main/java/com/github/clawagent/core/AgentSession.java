package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentSession 表示一段可恢复的用户会话。
 * M2 从会话事实源开始建设，后续模型消息、摘要、长期记忆提升都会挂到 sessionId。
 */
public class AgentSession {
    /** 会话 ID。 */
    private final String id;
    /** 会话标题。 */
    private final String title;
    /** 会话来源渠道，例如 webui、automation、api。 */
    private final String channelId;
    /** 会话所属用户 ID。 */
    private final String userId;
    /** 会话轻量扩展元信息。 */
    private final Map<String, String> metadata;
    /** 会话创建时间。 */
    private final Instant createdAt;
    /** 会话最后更新时间。 */
    private Instant updatedAt;
    /** 会话最后活跃时间。 */
    private Instant lastActiveAt;
    /** 会话摘要，用于历史恢复和长期记忆提升。 */
    private String summary;

    /**
     * 创建新会话。
     *
     * @param id 会话 ID。
     * @param title 会话标题。
     * @param channelId 会话来源渠道。
     * @param userId 会话所属用户 ID。
     * @param metadata 会话轻量扩展元信息。
     */
    public AgentSession(String id, String title, String channelId, String userId, Map<String, String> metadata) {
        this(id, title, channelId, userId, metadata, Instant.now(), null, null, null);
    }

    /**
     * 创建或恢复会话。
     *
     * @param id 会话 ID。
     * @param title 会话标题。
     * @param channelId 会话来源渠道。
     * @param userId 会话所属用户 ID。
     * @param metadata 会话轻量扩展元信息。
     * @param createdAt 会话创建时间。
     * @param updatedAt 会话最后更新时间。
     * @param lastActiveAt 会话最后活跃时间。
     * @param summary 会话摘要。
     */
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
    public String workspaceId() { return firstMetadata("workspaceId", "workspace.id"); }
    public String getWorkspaceId() { return workspaceId(); }
    public String workspaceName() { return firstMetadata("workspaceName", "workspace.name"); }
    public String getWorkspaceName() { return workspaceName(); }
    public String workspaceRoot() { return firstMetadata("workspaceRoot", "workspace.root", "workspace.projectPath", "projectPath", "activeProjectPath"); }
    public String getWorkspaceRoot() { return workspaceRoot(); }
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

    private String firstMetadata(String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
