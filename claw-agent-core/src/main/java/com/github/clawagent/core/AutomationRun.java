package com.github.clawagent.core;

import java.time.Instant;

/**
 * 自动化单次执行记录。
 *
 * @param id 单次运行记录 ID。
 * @param automationId 关联的自动化定义 ID。
 * @param taskId 本次运行提交后生成的 Agent 任务 ID。
 * @param status 本次运行状态。
 * @param startedAt 开始执行时间。
 * @param finishedAt 结束执行时间。
 * @param error 失败原因；成功时为空。
 */
public record AutomationRun(
        String id,
        String automationId,
        String taskId,
        AutomationRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        String error) {
}
