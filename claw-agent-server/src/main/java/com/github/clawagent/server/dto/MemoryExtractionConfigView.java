package com.github.clawagent.server.dto;

/**
 * 候选记忆提炼配置视图。
 *
 * @param enabled 是否启用候选记忆提炼。
 * @param mode 处理策略：after-task-async 或 batch。
 * @param intervalSeconds 定时批处理间隔秒数。
 * @param batchSize 单次后台批处理数量。
 */
public record MemoryExtractionConfigView(
        boolean enabled,
        String mode,
        long intervalSeconds,
        int batchSize
) {
}
