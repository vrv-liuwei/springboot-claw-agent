package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 批量派发子 Agent 的请求。
 * 上层可以先完成任务拆分，再把多个只读子任务交给后端并行或顺序调度。
 */
public record SubAgentBatchTaskRequest(
        List<SubAgentTaskRequest> tasks,
        Boolean parallel,
        Integer maxParallelism,
        String strategy,
        Map<String, String> metadata
) {
}
