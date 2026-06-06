package com.github.clawagent.core;

/**
 * AgentResult 是 Runtime 的稳定返回值，既返回最终回答，也返回 taskId/sessionId 方便追踪步骤和审计。
 *
 * @param taskId Runtime 创建或执行的任务 ID。
 * @param answer 最终回答内容。
 * @param status 任务最终状态。
 * @param sessionId 任务所属会话 ID。
 */
public record AgentResult(String taskId, String answer, TaskStatus status, String sessionId) {
    public AgentResult(String taskId, String answer, TaskStatus status) {
        this(taskId, answer, status, null);
    }
}
