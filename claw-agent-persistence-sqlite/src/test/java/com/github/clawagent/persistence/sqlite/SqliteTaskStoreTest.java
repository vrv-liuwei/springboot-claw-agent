package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.StepStatus;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteTaskStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void findSubTasksUsesStoredParentMetadata() {
        SqliteTaskStore store = new SqliteTaskStore(tempDir.resolve("clawagent.db"));
        store.saveTask(task("parent", "父任务", Map.of(), Instant.parse("2026-06-01T00:00:00Z")));
        store.saveTask(task("child-old", "子任务一",
                Map.of("agent.kind", "subagent", "agent.parentTaskId", "parent"),
                Instant.parse("2026-06-01T00:01:00Z")));
        store.saveTask(task("child-new", "子任务二",
                Map.of("agent.kind", "subagent", "agent.parentTaskId", "parent"),
                Instant.parse("2026-06-01T00:02:00Z")));
        store.saveTask(task("mentions-parent", "文本里提到 parent 但不是子任务",
                Map.of(), Instant.parse("2026-06-01T00:03:00Z")));

        assertEquals(List.of("child-new", "child-old"),
                store.findSubTasks("parent", 10).stream().map(AgentTask::id).toList());
    }

    @Test
    void globalAuditEventsCanBeQueriedWithoutTaskId() {
        SqliteTaskStore store = new SqliteTaskStore(tempDir.resolve("audit.db"));
        store.saveEvent(new AgentEvent("event-policy", "", "", "INFO", "policy.config_updated",
                "审批和本地权限策略已更新", Map.of("source", "admin.config.policy")));

        assertEquals(List.of("event-policy"),
                store.findEvents(null, null, "", "policy.config_updated", "", "", 10)
                        .stream()
                        .map(AgentEvent::id)
                        .toList());
    }

    @Test
    void searchStepsCanFilterByToolIdAndRiskLevel() {
        SqliteTaskStore store = new SqliteTaskStore(tempDir.resolve("steps.db"));
        store.saveStep(step("step-low", "task-a", "builtin.execute.command",
                Map.of("command", "git status", "riskLevel", "low"), "clean", null,
                Instant.parse("2026-06-01T00:00:00Z"), StepStatus.SUCCEEDED));
        store.saveStep(step("step-high", "task-b", "builtin.process.start",
                Map.of("command", "npm run dev", "riskLevel", "high"), null, "blocked",
                Instant.parse("2026-06-02T00:00:00Z"), StepStatus.FAILED));

        assertEquals(List.of("step-high"),
                store.searchSteps("", "", "", "process.start", "high", 10)
                        .stream()
                        .map(AgentStep::id)
                        .toList());
        assertEquals(List.of("step-low"),
                store.searchSteps("", "", "", "execute", "low", 10)
                        .stream()
                        .map(AgentStep::id)
                        .toList());
    }

    private AgentTask task(String id, String input, Map<String, String> metadata, Instant createdAt) {
        return new AgentTask(id, input, "session-a", "webui", "alice", metadata,
                createdAt, createdAt, TaskStatus.RUNNING, "");
    }

    private AgentStep step(String id, String taskId, String name, Map<String, String> input,
                           String output, String error, Instant startedAt, StepStatus status) {
        return new AgentStep(id, taskId, StepType.TOOL_CALL, name, input,
                startedAt, startedAt.plusSeconds(1), status, output, error);
    }
}
