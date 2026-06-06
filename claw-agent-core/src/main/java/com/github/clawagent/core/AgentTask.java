package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentTask 是 Harness 的任务事实源。
 * 所有执行步骤、恢复、审批和审计都以 taskId 作为关联键。
 */
public class AgentTask {
    /** 任务 ID。 */
    private final String id;
    /** 用户原始输入。 */
    private final String input;
    /** 任务所属会话 ID。 */
    private final String sessionId;
    /** 任务来源渠道，例如 webui、automation、api。 */
    private final String channelId;
    /** 任务所属用户 ID。 */
    private final String userId;
    /** 任务轻量扩展元信息。 */
    private final Map<String, String> metadata;
    /** 任务创建时间。 */
    private final Instant createdAt;
    /** 任务最后更新时间。 */
    private Instant updatedAt;
    /** 任务状态。 */
    private TaskStatus status;
    /** 任务最终回答。 */
    private String finalAnswer;

    /**
     * 从请求创建新任务。
     *
     * @param id 任务 ID。
     * @param request Agent 请求对象。
     */
    public AgentTask(String id, AgentRequest request) {
        this(id, request.input(), request.sessionId(), request.channelId(), request.userId(), request.metadata(),
                Instant.now(), null, TaskStatus.PENDING, null);
    }

    /**
     * 创建或恢复任务。
     *
     * @param id 任务 ID。
     * @param input 用户原始输入。
     * @param sessionId 任务所属会话 ID。
     * @param channelId 任务来源渠道。
     * @param userId 任务所属用户 ID。
     * @param metadata 任务轻量扩展元信息。
     * @param createdAt 任务创建时间。
     * @param updatedAt 任务最后更新时间。
     * @param status 任务状态。
     * @param finalAnswer 任务最终回答。
     */
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
