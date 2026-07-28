package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 批量子 Agent 派发结果。
 * 单个子任务失败不会吞掉其它子任务结果，方便前端展示部分成功和失败原因。
 */
public record SubAgentBatchTaskResponse(
        String parentTaskId,
        String dispatchId,
        String strategy,
        boolean parallel,
        int maxParallelism,
        int total,
        int succeeded,
        int failed,
        List<SubAgentTaskResponse> tasks,
        Map<Integer, String> errors
) {
}
