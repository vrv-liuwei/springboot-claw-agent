package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 创建子 Agent 任务的最小请求。
 * 当前只开放只读隔离子任务，后续再按策略扩展写入型 worker。
 */
public record SubAgentTaskRequest(
        String input,
        String role,
        String isolation,
        Map<String, String> metadata
) {
}
