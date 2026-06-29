package com.github.clawagent.server.dto;

/**
 * 长期记忆治理配置视图。
 *
 * @param staleAfterDays 超过该天数未命中后开始降权。
 * @param veryStaleAfterDays 超过该天数未命中后降权趋近上限。
 * @param autoArchiveEnabled 是否启用自动归档。
 * @param archiveAfterDays 超过该天数未命中时归档。
 * @param archiveBelowQuality 质量分低于该阈值时归档。
 */
public record MemoryGovernanceConfigView(
        int staleAfterDays,
        int veryStaleAfterDays,
        boolean autoArchiveEnabled,
        int archiveAfterDays,
        double archiveBelowQuality
) {
}
