package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 单层审批策略的实际命中结果。
 * 和配置页的静态 resolutionOrder 不同，这里展示一次具体任务请求命中了哪些策略层。
 */
public record PolicyResolveLayerView(
        int order,
        String source,
        String scope,
        String mode,
        List<String> approvedToolIds,
        String reason,
        boolean effective
) {
}
