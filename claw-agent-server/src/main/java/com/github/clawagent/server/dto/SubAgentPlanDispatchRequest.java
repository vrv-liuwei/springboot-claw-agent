package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 从计划项派发子 Agent 的请求。
 * 计划本身负责拆解目标，本请求只控制派发方式和附加 metadata。
 */
public record SubAgentPlanDispatchRequest(
        String planId,
        Boolean parallel,
        Integer maxParallelism,
        String strategy,
        Boolean includeHighRisk,
        String dispatchMode,
        Map<String, String> metadata
) {
}
