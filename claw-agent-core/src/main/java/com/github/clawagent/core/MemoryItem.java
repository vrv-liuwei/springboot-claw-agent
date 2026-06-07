package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 长期记忆条目。
 * <p>
 * 记忆条目只保存提炼后的稳定事实，不保存完整聊天原文；task 只能作为来源，不作为长期 scope。
 * </p>
 */
public class MemoryItem {
    /** 记忆 ID。 */
    private final String id;
    /** 用户 ID，所有记忆读写和检索都必须按用户隔离。 */
    private final String userId;
    /** 记忆范围：global、channel、session，workspace 仅预留。 */
    private final String scopeType;
    /** 记忆范围 ID，例如 channelId 或 sessionId；global 可为空。 */
    private final String scopeId;
    /** 记忆类型，例如 preference、decision、lesson、project_fact、rule。 */
    private final String type;
    /** 记忆状态：pending、active、disabled、conflict、archived。 */
    private final String status;
    /** 记忆正文，进入检索和模型上下文的主要内容。 */
    private final String content;
    /** 记忆短摘要，用于页面列表和上下文预览。 */
    private final String summary;
    /** 来源会话 ID，用于追溯记忆来自哪个会话。 */
    private final String sourceSessionId;
    /** 来源任务 ID，用于追溯记忆来自哪次执行。 */
    private final String sourceTaskId;
    /** 重要性评分，范围由 provider 解释，默认 0。 */
    private final double importance;
    /** 置信度评分，范围由 provider 解释，默认 0。 */
    private final double confidence;
    /** 轻量扩展元信息。 */
    private final Map<String, String> metadata;
    /** 创建时间。 */
    private final Instant createdAt;
    /** 更新时间。 */
    private final Instant updatedAt;

    /**
     * 创建记忆条目。
     *
     * @param id 记忆 ID。
     * @param userId 用户 ID。
     * @param scopeType 记忆范围。
     * @param scopeId 记忆范围 ID。
     * @param type 记忆类型。
     * @param status 记忆状态。
     * @param content 记忆正文。
     * @param summary 记忆短摘要。
     * @param sourceSessionId 来源会话 ID。
     * @param sourceTaskId 来源任务 ID。
     * @param importance 重要性评分。
     * @param confidence 置信度评分。
     * @param metadata 轻量扩展元信息。
     * @param createdAt 创建时间。
     * @param updatedAt 更新时间。
     */
    public MemoryItem(String id,
                      String userId,
                      String scopeType,
                      String scopeId,
                      String type,
                      String status,
                      String content,
                      String summary,
                      String sourceSessionId,
                      String sourceTaskId,
                      double importance,
                      double confidence,
                      Map<String, String> metadata,
                      Instant createdAt,
                      Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.type = type;
        this.status = status;
        this.content = content;
        this.summary = summary;
        this.sourceSessionId = sourceSessionId;
        this.sourceTaskId = sourceTaskId;
        this.importance = importance;
        this.confidence = confidence;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String userId() { return userId; }
    public String getUserId() { return userId; }
    public String scopeType() { return scopeType; }
    public String getScopeType() { return scopeType; }
    public String scopeId() { return scopeId; }
    public String getScopeId() { return scopeId; }
    public String type() { return type; }
    public String getType() { return type; }
    public String status() { return status; }
    public String getStatus() { return status; }
    public String content() { return content; }
    public String getContent() { return content; }
    public String summary() { return summary; }
    public String getSummary() { return summary; }
    public String sourceSessionId() { return sourceSessionId; }
    public String getSourceSessionId() { return sourceSessionId; }
    public String sourceTaskId() { return sourceTaskId; }
    public String getSourceTaskId() { return sourceTaskId; }
    public double importance() { return importance; }
    public double getImportance() { return importance; }
    public double confidence() { return confidence; }
    public double getConfidence() { return confidence; }
    public Map<String, String> metadata() { return metadata; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant createdAt() { return createdAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
