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
        this.id = id;
        this.input = request.input();
        this.sessionId = request.sessionId();
        this.channelId = request.channelId();
        this.userId = request.userId();
        this.metadata = new LinkedHashMap<>(request.metadata());
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = TaskStatus.PENDING;
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
