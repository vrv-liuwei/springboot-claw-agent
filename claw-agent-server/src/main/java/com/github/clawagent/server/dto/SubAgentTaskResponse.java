package com.github.clawagent.server.dto;

import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentTask;

/**
 * 子 Agent 提交结果。
 * 同时返回 childTaskId 和 task，方便前端不再额外请求一次任务详情。
 */
public record SubAgentTaskResponse(
        String parentTaskId,
        String childTaskId,
        String role,
        String isolation,
        AgentResult result,
        AgentTask task
) {
}
