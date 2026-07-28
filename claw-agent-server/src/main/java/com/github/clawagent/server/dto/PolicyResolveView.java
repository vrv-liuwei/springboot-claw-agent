package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 一次任务策略合并的可解释结果。
 * 返回最终 metadata，便于前端和排障工具确认 Runtime 实际会收到什么策略字段。
 */
public record PolicyResolveView(
        String channelId,
        String userId,
        String effectiveMode,
        String source,
        String scope,
        String reason,
        List<String> approvedToolIds,
        List<PolicyResolveLayerView> layers,
        Map<String, String> effectiveMetadata
) {
}
