package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 创建子 Agent 任务的最小请求。
 * 当前只开放只读隔离子任务；workerMode 先用于记录调用方是否请求独立进程 worker。
 */
public record SubAgentTaskRequest(
        String input,
        String role,
        String isolation,
        String workerMode,
        Map<String, String> metadata
) {
}
