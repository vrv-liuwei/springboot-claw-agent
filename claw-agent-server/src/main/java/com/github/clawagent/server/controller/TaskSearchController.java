package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.TaskStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨任务历史检索接口。
 * 单任务回放仍走 TaskController/SessionController；这里只提供全局只读查询能力。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskSearchController {
    private final TaskStore taskStore;

    public TaskSearchController(@Qualifier("taskStore") TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    /**
     * 按关键词、状态、渠道、用户或会话检索历史任务。
     */
    @GetMapping("/tasks/search")
    public List<AgentTask> searchTasks(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "channelId", required = false) String channelId,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return taskStore.searchTasks(query, status, channelId, userId, sessionId, safeLimit(limit));
    }

    /**
     * 跨任务检索执行步骤，用于定位历史工具调用、失败步骤和输出片段。
     */
    @GetMapping("/steps/search")
    public List<AgentStep> searchSteps(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "toolId", required = false) String toolId,
            @RequestParam(name = "riskLevel", required = false) String riskLevel,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return taskStore.searchSteps(query, status, taskId, toolId, riskLevel, safeLimit(limit));
    }

    private int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }
}
