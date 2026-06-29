package com.github.clawagent.server.dto;

/**
 * 策略解析层级说明。
 * 用于管理台解释当前策略来自哪里，以及后续企业维度策略合并的预留顺序。
 */
public record PolicyResolutionLayerView(
        int order,
        String key,
        String scope,
        String source,
        String status,
        String description
) {
}
