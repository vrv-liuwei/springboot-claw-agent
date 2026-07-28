package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TodoItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAgentRuntimeTodoSelectionTest {

    @Test
    void planMetadataWinsOverAnOlderPlanInTheSameSession() {
        Instant now = Instant.now();
        AgentTask currentTask = new AgentTask("task-resume", new AgentRequest(
                "按已确认计划继续执行",
                "session-1",
                "webui",
                "console",
                Map.of("plan.id", "plan-new")));
        TodoItem oldPlan = todo("old-todo", "old-task", 1, "旧计划", "pending", "plan-old", now.minusSeconds(20));
        TodoItem newPlan = todo("new-todo", "new-task", 1, "当前计划", "pending", "plan-new", now.minusSeconds(10));

        List<TodoItem> selected = DefaultAgentRuntime.selectRelevantTodos(currentTask, List.of(oldPlan, newPlan));

        assertEquals(List.of("new-todo"), selected.stream().map(TodoItem::id).toList());
    }

    private TodoItem todo(String id, String taskId, int order, String title, String status,
                          String planId, Instant createdAt) {
        return new TodoItem(id, "session-1", taskId, order, title, title, status,
                Map.of("planId", planId), createdAt, createdAt);
    }
}
