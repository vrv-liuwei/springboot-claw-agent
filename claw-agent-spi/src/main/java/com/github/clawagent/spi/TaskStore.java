package com.github.clawagent.spi;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;

import java.util.List;
import java.util.Optional;

public interface TaskStore {
    void saveTask(AgentTask task);

    void updateTask(AgentTask task);

    Optional<AgentTask> findTask(String taskId);

    List<AgentTask> findTasksBySession(String sessionId, int limit);

    List<AgentTask> findSubTasks(String parentTaskId, int limit);

    List<AgentTask> searchTasks(String query, String status, String channelId, String userId, String sessionId, int limit);

    void saveStep(AgentStep step);

    List<AgentStep> findSteps(String taskId);

    /**
     * 兼容旧调用方的步骤检索入口。
     * 新增过滤条件必须挂到重载方法，避免历史插件或测试因为 SPI 扩展立即编译失败。
     */
    default List<AgentStep> searchSteps(String query, String status, String taskId, int limit) {
        return searchSteps(query, status, taskId, null, null, limit);
    }

    /**
     * 跨任务检索工具步骤。
     * toolId 用于定位某类工具调用，riskLevel 用于快速找出高危或不确定风险的历史执行。
     */
    List<AgentStep> searchSteps(String query, String status, String taskId, String toolId, String riskLevel, int limit);
}
