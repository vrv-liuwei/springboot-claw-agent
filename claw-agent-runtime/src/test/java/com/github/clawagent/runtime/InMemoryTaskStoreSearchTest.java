package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.StepStatus;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryTaskStoreSearchTest {
    @Test
    void searchTasksMatchesContentAndFilters() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task("task-old", "修复登录", "session-a", "webui", "alice",
                Map.of("workspaceRoot", "D:/workspace/admin-system"), Instant.parse("2026-06-01T00:00:00Z"),
                TaskStatus.COMPLETED, "登录修复完成"));
        store.saveTask(task("task-new", "生成报表", "session-b", "api", "bob",
                Map.of("workspaceRoot", "D:/workspace/report-system"), Instant.parse("2026-06-02T00:00:00Z"),
                TaskStatus.FAILED, "数据库连接失败"));

        assertEquals(List.of("task-old"), ids(store.searchTasks("登录", "", "", "", "", 10)));
        assertEquals(List.of("task-new"), ids(store.searchTasks("report-system", "", "", "", "", 10)));
        assertEquals(List.of("task-new"), ids(store.searchTasks("", "FAILED", "api", "bob", "session-b", 10)));
    }

    @Test
    void findSubTasksUsesExplicitParentMetadata() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveTask(task("parent", "父任务", "session-a", "webui", "alice",
                Map.of(), Instant.parse("2026-06-01T00:00:00Z"), TaskStatus.RUNNING, ""));
        store.saveTask(task("child-old", "子任务一", "session-a", "webui", "alice",
                Map.of("agent.kind", "subagent", "agent.parentTaskId", "parent"),
                Instant.parse("2026-06-01T00:01:00Z"), TaskStatus.RUNNING, ""));
        store.saveTask(task("child-new", "子任务二", "session-a", "webui", "alice",
                Map.of("agent.kind", "subagent", "agent.parentTaskId", "parent"),
                Instant.parse("2026-06-01T00:02:00Z"), TaskStatus.COMPLETED, ""));
        store.saveTask(task("mentions-parent", "文本里提到 parent 但不是子任务", "session-a", "webui", "alice",
                Map.of(), Instant.parse("2026-06-01T00:03:00Z"), TaskStatus.COMPLETED, ""));

        assertEquals(List.of("child-new", "child-old"), ids(store.findSubTasks("parent", 10)));
    }

    @Test
    void searchStepsMatchesToolDataAndKeepsNewestFirst() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        store.saveStep(step("step-old", "task-a", "builtin.execute.command",
                Map.of("command", "git status", "riskLevel", "low"), "clean tree", null,
                Instant.parse("2026-06-01T00:00:00Z"), StepStatus.SUCCEEDED));
        store.saveStep(step("step-new", "task-b", "builtin.filesystem.read_text_file",
                Map.of("path", "pom.xml", "riskLevel", "high"), null, "read pom failed",
                Instant.parse("2026-06-02T00:00:00Z"), StepStatus.FAILED));

        assertEquals(List.of("step-old"), stepIds(store.searchSteps("git status", "", "", 10)));
        assertEquals(List.of("step-new"), stepIds(store.searchSteps("pom failed", "FAILED", "task-b", 10)));
        assertEquals(List.of("step-new"), stepIds(store.searchSteps("", "", "", "filesystem", "high", 10)));
        assertEquals(List.of("step-old"), stepIds(store.searchSteps("", "", "", "execute.command", "low", 10)));
        assertEquals(List.of("step-new", "step-old"), stepIds(store.searchSteps("", "", "", 10)));
    }

    private AgentTask task(String id, String input, String sessionId, String channelId, String userId,
                           Map<String, String> metadata, Instant createdAt, TaskStatus status, String finalAnswer) {
        return new AgentTask(id, input, sessionId, channelId, userId, metadata,
                createdAt, createdAt, status, finalAnswer);
    }

    private AgentStep step(String id, String taskId, String name, Map<String, String> input,
                           String output, String error, Instant startedAt, StepStatus status) {
        return new AgentStep(id, taskId, StepType.TOOL_CALL, name, input,
                startedAt, startedAt.plusSeconds(1), status, output, error);
    }

    private List<String> ids(List<AgentTask> tasks) {
        return tasks.stream().map(AgentTask::id).toList();
    }

    private List<String> stepIds(List<AgentStep> steps) {
        return steps.stream().map(AgentStep::id).toList();
    }
}
