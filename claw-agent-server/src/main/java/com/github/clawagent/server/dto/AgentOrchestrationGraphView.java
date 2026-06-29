package com.github.clawagent.server.dto;

import com.github.clawagent.core.TaskStatus;

import java.time.Instant;
import java.util.List;

/**
 * 父子 Agent 编排图的轻量视图。
 * 只复用任务 metadata 中的父子关系，不引入新的编排存储。
 */
public record AgentOrchestrationGraphView(
        String rootTaskId,
        int totalTasks,
        int runningCount,
        int waitingCount,
        int completedCount,
        int failedCount,
        int maxDepth,
        boolean truncated,
        List<Node> nodes,
        List<Edge> edges
) {
    public record Node(
            String taskId,
            String parentTaskId,
            String role,
            String isolation,
            TaskStatus status,
            String input,
            int depth,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Edge(
            String parentTaskId,
            String childTaskId,
            String role,
            String isolation
    ) {
    }
}
