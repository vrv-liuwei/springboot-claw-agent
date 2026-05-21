package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.TaskStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryTaskStore implements TaskStore {
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
    public synchronized void saveStep(AgentStep step) {
        steps.computeIfAbsent(step.taskId(), key -> new ArrayList<>()).add(step);
    }

    @Override
    public synchronized List<AgentStep> findSteps(String taskId) {
        return new ArrayList<>(steps.getOrDefault(taskId, List.of()));
    }
}
