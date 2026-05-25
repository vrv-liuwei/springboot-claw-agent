package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentTask 是 Harness 的任务事实源。
 * 所有执行步骤、恢复、审批和审计都以 taskId 作为关联键。
 */
public class AgentTask {
    private final String id;
    private final String input;
    private final String sessionId;
    private final String channelId;
    private final String userId;
    private final Map<String, String> metadata;
    private final Instant createdAt;
    private Instant updatedAt;
    private TaskStatus status;
    private String finalAnswer;

    public AgentTask(String id, AgentRequest request) {
        this(id, request.input(), request.sessionId(), request.channelId(), request.userId(), request.metadata(),
                Instant.now(), null, TaskStatus.PENDING, null);
    }

    public AgentTask(String id, String input, String sessionId, String channelId, String userId, Map<String, String> metadata,
                     Instant createdAt, Instant updatedAt, TaskStatus status, String finalAnswer) {
        this.id = id;
        this.input = input;
        this.sessionId = sessionId;
        this.channelId = channelId;
        this.userId = userId;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        // 历史任务从数据库恢复时保留原始创建/更新时间，避免刷新页面导致时间跳动。
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
        this.status = status == null ? TaskStatus.PENDING : status;
        this.finalAnswer = finalAnswer;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String input() { return input; }
    public String getInput() { return input; }
    public String sessionId() { return sessionId; }
    public String getSessionId() { return sessionId; }
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
    public TaskStatus status() { return status; }
    public TaskStatus getStatus() { return status; }
    public String finalAnswer() { return finalAnswer; }
    public String getFinalAnswer() { return finalAnswer; }

    public void markStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void complete(String finalAnswer) {
        this.finalAnswer = finalAnswer;
        markStatus(TaskStatus.COMPLETED);
    }
}
