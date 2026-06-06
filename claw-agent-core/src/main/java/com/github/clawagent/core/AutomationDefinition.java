package com.github.clawagent.core;

import java.time.Instant;
import java.util.Map;

/**
 * 智能体自动化定义。
 *
 * @param id 自动化定义 ID。
 * @param name 自动化名称，用于管理台展示。
 * @param prompt 每次触发时提交给 Agent 的任务内容。
 * @param sessionId 默认会话 ID；为空时运行时可创建新会话。
 * @param channelId 触发渠道，例如 automation。
 * @param userId 自动化所属用户 ID。
 * @param scheduleType 调度类型，例如一次性、固定间隔、cron。
 * @param cronExpression cron 调度表达式，仅 scheduleType 为 CRON 时使用。
 * @param intervalSeconds 固定间隔秒数，仅 scheduleType 为 INTERVAL 时使用。
 * @param timezone 调度时区，例如 Asia/Shanghai。
 * @param nextRunAt 下一次计划触发时间。
 * @param lastRunAt 最近一次触发时间。
 * @param status 自动化启停状态。
 * @param metadata 轻量扩展元信息。
 * @param createdAt 创建时间。
 * @param updatedAt 最后更新时间。
 */
public record AutomationDefinition(
        String id,
        String name,
        String prompt,
        String sessionId,
        String channelId,
        String userId,
        AutomationScheduleType scheduleType,
        String cronExpression,
        Long intervalSeconds,
        String timezone,
        Instant nextRunAt,
        Instant lastRunAt,
        AutomationStatus status,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public AutomationDefinition {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
