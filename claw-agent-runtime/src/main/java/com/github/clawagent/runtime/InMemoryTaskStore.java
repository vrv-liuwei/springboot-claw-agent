package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.TaskStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryTaskStore implements TaskStore, AgentDataCleaner {
    private final Map<String, AgentTask> tasks = new LinkedHashMap<>();
    private final Map<String, List<AgentStep>> steps = new LinkedHashMap<>();

    @Override
    public synchronized void saveTask(AgentTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public synchronized void updateTask(AgentTask task) {
        tasks.put(task.id(), task);
    }

    @Override
    public synchronized Optional<AgentTask> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized List<AgentTask> findTasksBySession(String sessionId, int limit) {
        return tasks.values().stream()
                .filter(task -> sessionId.equals(task.sessionId()))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized List<AgentTask> findSubTasks(String parentTaskId, int limit) {
        return tasks.values().stream()
                // 子 Agent 关系以 metadata 显式保存，查询时不能依赖模糊搜索命中。
                .filter(task -> parentTaskId.equals(task.metadata().get("agent.parentTaskId")))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized List<AgentTask> searchTasks(String query, String status, String channelId, String userId, String sessionId, int limit) {
        String normalizedQuery = normalize(query);
        return tasks.values().stream()
                .filter(task -> normalizedQuery.isBlank()
                        || normalize(task.id()).contains(normalizedQuery)
                        || normalize(task.input()).contains(normalizedQuery)
                        || normalize(task.finalAnswer()).contains(normalizedQuery)
                        || normalize(task.metadata().toString()).contains(normalizedQuery))
                .filter(task -> status == null || status.isBlank() || status.equalsIgnoreCase(task.status().name()))
                .filter(task -> channelId == null || channelId.isBlank() || channelId.equals(task.channelId()))
                .filter(task -> userId == null || userId.isBlank() || userId.equals(task.userId()))
                .filter(task -> sessionId == null || sessionId.isBlank() || sessionId.equals(task.sessionId()))
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized void saveStep(AgentStep step) {
        steps.computeIfAbsent(step.taskId(), key -> new ArrayList<>()).add(step);
    }

    @Override
    public synchronized List<AgentStep> findSteps(String taskId) {
        return new ArrayList<>(steps.getOrDefault(taskId, List.of()));
    }

    @Override
    public synchronized List<AgentStep> searchSteps(String query, String status, String taskId, String toolId, String riskLevel, int limit) {
        String normalizedQuery = normalize(query);
        String normalizedToolId = normalize(toolId);
        String normalizedRiskLevel = normalize(riskLevel);
        return steps.values().stream()
                .flatMap(List::stream)
                .filter(step -> taskId == null || taskId.isBlank() || taskId.equals(step.taskId()))
                .filter(step -> status == null || status.isBlank() || status.equalsIgnoreCase(step.status().name()))
                // toolId/riskLevel 是排查历史工具调用最常用的两个维度，内存和 SQLite store 保持同一语义。
                .filter(step -> normalizedToolId.isBlank() || normalize(step.name()).contains(normalizedToolId))
                .filter(step -> normalizedRiskLevel.isBlank()
                        || normalizedRiskLevel.equals(normalize(step.input().get("riskLevel"))))
                .filter(step -> normalizedQuery.isBlank()
                        || normalize(step.id()).contains(normalizedQuery)
                        || normalize(step.name()).contains(normalizedQuery)
                        || normalize(step.input().toString()).contains(normalizedQuery)
                        || normalize(step.output()).contains(normalizedQuery)
                        || normalize(step.error()).contains(normalizedQuery))
                .sorted((left, right) -> right.startedAt().compareTo(left.startedAt()))
                .limit(Math.max(1, limit))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    @Override
    public synchronized void clearAllAgentData() {
        // 任务和步骤是同一份执行历史，清空时必须一起删除。
        tasks.clear();
        steps.clear();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
