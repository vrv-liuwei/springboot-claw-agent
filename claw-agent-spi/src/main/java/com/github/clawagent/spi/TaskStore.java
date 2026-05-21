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

    void saveStep(AgentStep step);

    List<AgentStep> findSteps(String taskId);
}
