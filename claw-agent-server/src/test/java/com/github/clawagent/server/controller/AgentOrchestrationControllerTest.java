package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.runtime.InMemoryPlanStore;
import com.github.clawagent.runtime.InMemoryTaskStore;
import com.github.clawagent.server.dto.SubAgentBatchTaskRequest;
import com.github.clawagent.server.dto.SubAgentBatchTaskResponse;
import com.github.clawagent.server.dto.SubAgentPlanDispatchRequest;
import com.github.clawagent.server.dto.SubAgentTaskRequest;
import com.github.clawagent.server.service.SubAgentWorkerDispatchResult;
import com.github.clawagent.server.service.SubAgentWorkerDispatcher;
import com.github.clawagent.spring.ClawAgentProperties;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestrationControllerTest {

    @Test
    void batchDispatchCreatesReadOnlyChildrenAndDoesNotInheritApproval() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of(
                "workspaceRoot", "D:\\workspace\\demo",
                "approvedToolIds", "builtin.execute.command",
                "allowHighRiskTools", "true"));
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(
                        new SubAgentTaskRequest("梳理 controller", "reviewer", "write", "process", Map.of("scope", "controller")),
                        new SubAgentTaskRequest("梳理 service", "reader", "", "", Map.of("scope", "service"))),
                false,
                null,
                "fanout",
                Map.of("batch", "daily-review")));

        assertEquals(parent.id(), response.parentTaskId());
        assertEquals("fanout", response.strategy());
        assertFalse(response.parallel());
        assertEquals(1, response.maxParallelism());
        assertEquals(2, response.total());
        assertEquals(2, response.succeeded());
        assertEquals(0, response.failed());
        assertTrue(response.errors().isEmpty());

        AgentTask child = response.tasks().get(0).task();
        Map<String, String> metadata = child.metadata();
        assertEquals(parent.id(), metadata.get("agent.parentTaskId"));
        assertEquals("subagent", metadata.get("agent.kind"));
        assertEquals("read-only", metadata.get("agent.isolation"));
        assertEquals("write", metadata.get("agent.isolation.requested"));
        assertEquals("read-only", metadata.get("agent.isolation.effective"));
        assertEquals("metadata-read-only", metadata.get("agent.isolation.profile"));
        assertEquals("tool-guard", metadata.get("agent.isolation.enforcement"));
        assertEquals("false", metadata.get("agent.worker.eligible"));
        assertEquals("not-started", metadata.get("agent.worker.mode"));
        assertEquals("process", metadata.get("agent.worker.requested"));
        assertEquals("not-started", metadata.get("agent.worker.effective"));
        assertEquals("false", metadata.get("agent.worker.configured"));
        assertTrue(metadata.get("agent.worker.reason").contains("未启用"));
        assertTrue(metadata.get("agent.isolation.overrideReason").contains("降级"));
        assertEquals("ask", metadata.get("toolPermissionMode"));
        assertEquals("fanout", metadata.get("agent.dispatch.strategy"));
        assertEquals("false", metadata.get("agent.dispatch.parallel"));
        assertEquals("1", metadata.get("agent.dispatch.maxParallelism"));
        assertEquals("manual", metadata.get("agent.split.source"));
        assertEquals("fanout", metadata.get("agent.split.strategy"));
        assertEquals("fanout", metadata.get("agent.split.profile"));
        assertEquals("request-role", metadata.get("agent.split.rolePolicy"));
        assertEquals("0", metadata.get("agent.dispatch.index"));
        assertEquals("2", metadata.get("agent.dispatch.total"));
        assertEquals("daily-review", metadata.get("batch"));
        assertEquals("controller", metadata.get("scope"));
        assertEquals("D:\\workspace\\demo", metadata.get("workspaceRoot"));
        assertFalse(metadata.containsKey("approvedToolIds"));
        assertFalse(metadata.containsKey("allowHighRiskTools"));
        assertNotEquals(parent.id(), child.id());
        assertTrue(child.input().startsWith("[子 Agent role=reviewer isolation=read-only]"));
    }

    @Test
    void configuredWorkerRequestIsAuditedAsEligibleButNotStartedUntilDispatcherExists() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        ClawAgentProperties properties = new ClawAgentProperties();
        properties.getAgents().getWorker().setEnabled(true);
        properties.getAgents().getWorker().setMode("external-process");
        properties.getAgents().getWorker().setCommand("java -jar claw-agent-worker-runtime.jar");
        properties.getAgents().getWorker().setMaxConcurrent(3);
        properties.getAgents().getWorker().setAcquireTimeoutMs(1200);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore(), properties);

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(new SubAgentTaskRequest("检查 worker 配置", "reader", "", "process", Map.of())),
                false,
                null,
                "fanout",
                Map.of()));

        Map<String, String> metadata = response.tasks().get(0).task().metadata();
        assertEquals("process", metadata.get("agent.worker.requested"));
        assertEquals("true", metadata.get("agent.worker.configured"));
        assertEquals("true", metadata.get("agent.worker.eligible"));
        assertEquals("external-process", metadata.get("agent.worker.mode"));
        assertEquals("not-started", metadata.get("agent.worker.effective"));
        assertEquals("3", metadata.get("agent.worker.maxConcurrent"));
        assertEquals("1200", metadata.get("agent.worker.acquireTimeoutMs"));
        assertTrue(metadata.get("agent.worker.reason").contains("dispatcher 尚未接入"));
    }

    @Test
    void configuredWorkerRequestDispatchesThroughExternalWorkerPath() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        ClawAgentProperties properties = configuredWorkerProperties();
        FakeWorkerDispatcher dispatcher = new FakeWorkerDispatcher();
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore(), properties, dispatcher);

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(new SubAgentTaskRequest("检查 worker 执行", "reader", "", "process", Map.of())),
                false,
                null,
                "fanout",
                Map.of()));

        AgentTask child = response.tasks().get(0).task();
        assertEquals(0, runtime.submitCount);
        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(TaskStatus.COMPLETED, child.status());
        assertEquals("worker ok", child.finalAnswer());
        assertEquals("external-process", child.metadata().get("agent.worker.effective"));
        assertEquals("worker-process+tool-guard", child.metadata().get("agent.isolation.enforcement"));
        assertEquals("yes", child.metadata().get("worker.fake"));
        assertTrue(child.metadata().get("agent.worker.reason").contains("worker 执行"));
    }

    @Test
    void batchDispatchRejectsBlankEffectiveTasks() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskRequest request = new SubAgentBatchTaskRequest(
                List.of(new SubAgentTaskRequest("  ", "reader", "", "", Map.of())),
                false,
                null,
                "fanout",
                Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createSubTasks(parent.id(), request));
        assertTrue(error.getMessage().contains("没有有效输入"));
    }

    @Test
    void batchDispatchKeepsPartialFailureVisible() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        runtime.failOnInput("失败子任务");
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(
                        new SubAgentTaskRequest("正常子任务", "reader", "", "", Map.of()),
                        new SubAgentTaskRequest("失败子任务", "reader", "", "", Map.of())),
                true,
                1,
                "parallel-check",
                Map.of()));

        assertTrue(response.parallel());
        assertEquals(1, response.maxParallelism());
        assertEquals(2, response.total());
        assertEquals(1, response.succeeded());
        assertEquals(1, response.failed());
        assertEquals(1, response.errors().size());
        assertTrue(response.errors().values().iterator().next().contains("模拟失败"));
    }

    @Test
    void parallelDispatchCapsMaxParallelismToTaskCount() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(
                        new SubAgentTaskRequest("子任务一", "reader", "", "", Map.of()),
                        new SubAgentTaskRequest("子任务二", "reader", "", "", Map.of()),
                        new SubAgentTaskRequest("子任务三", "reader", "", "", Map.of())),
                true,
                99,
                "parallel-cap",
                Map.of()));

        assertTrue(response.parallel());
        assertEquals(3, response.maxParallelism());
        assertEquals(3, response.succeeded());
        Map<String, String> metadata = response.tasks().get(0).task().metadata();
        assertEquals("parallel-cap", metadata.get("agent.split.strategy"));
        assertEquals("custom", metadata.get("agent.split.profile"));
        assertEquals("true", metadata.get("agent.dispatch.parallel"));
        assertEquals("3", metadata.get("agent.dispatch.maxParallelism"));
    }

    @Test
    void planDispatchCreatesSubAgentsFromPlanItems() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of("workspaceRoot", "D:\\workspace\\demo"));
        taskStore.saveTask(parent);
        PlanDraft plan = planDraft(parent.sessionId());
        planStore.savePlan(plan);
        AgentOrchestrationController controller = controller(runtime, taskStore, planStore);

        SubAgentBatchTaskResponse response = controller.createSubTasksFromPlan(parent.id(), new SubAgentPlanDispatchRequest(
                plan.id(),
                false,
                null,
                "",
                false,
                "",
                Map.of("dispatch.reason", "review-before-run")));

        assertEquals(1, response.total());
        assertEquals(1, response.succeeded());
        assertEquals("plan-items", response.strategy());
        AgentTask child = response.tasks().get(0).task();
        Map<String, String> metadata = child.metadata();
        assertEquals("plan", metadata.get("agent.split.source"));
        assertEquals("plan-items", metadata.get("agent.split.profile"));
        assertEquals("skip-high-or-approval", metadata.get("agent.split.highRiskPolicy"));
        assertEquals(plan.id(), metadata.get("plan.id"));
        assertEquals("1", metadata.get("plan.itemOrder"));
        assertEquals("low", metadata.get("plan.itemRiskLevel"));
        assertEquals("review-before-run", metadata.get("dispatch.reason"));
        assertEquals("read-only", metadata.get("agent.isolation"));
        assertTrue(child.input().contains("当前计划步骤 1"));
        assertFalse(child.input().contains("危险步骤"));
    }

    @Test
    void planDispatchExcludesApprovalRequiredItemsWhenHighRiskDisabled() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        PlanDraft plan = planDraftWithApprovalRequiredMediumStep(parent.sessionId());
        planStore.savePlan(plan);
        AgentOrchestrationController controller = controller(runtime, taskStore, planStore);

        SubAgentBatchTaskResponse response = controller.createSubTasksFromPlan(parent.id(), new SubAgentPlanDispatchRequest(
                plan.id(),
                false,
                null,
                "",
                false,
                "",
                Map.of()));

        assertEquals(1, response.total());
        AgentTask child = response.tasks().get(0).task();
        assertEquals("low", child.metadata().get("plan.itemRiskLevel"));
        assertTrue(child.input().contains("安全步骤"));
        assertFalse(child.input().contains("需要审批步骤"));
    }

    @Test
    void planAutoDispatchOnlyKeepsReadOnlyAnalysisItems() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        PlanDraft plan = planDraftWithAutoDispatchMixedSteps(parent.sessionId());
        planStore.savePlan(plan);
        AgentOrchestrationController controller = controller(runtime, taskStore, planStore);

        SubAgentBatchTaskResponse response = controller.createSubTasksFromPlan(parent.id(), new SubAgentPlanDispatchRequest(
                plan.id(),
                false,
                null,
                "",
                true,
                "auto",
                Map.of()));

        assertEquals(1, response.total());
        AgentTask child = response.tasks().get(0).task();
        Map<String, String> metadata = child.metadata();
        assertEquals("auto", metadata.get("agent.split.dispatchMode"));
        assertEquals("true", metadata.get("agent.split.autoCandidate"));
        assertTrue(child.input().contains("审查 controller"));
        assertFalse(child.input().contains("修改 service"));
        assertFalse(child.input().contains("执行测试"));
    }

    @Test
    void childMetadataCannotOverrideReadOnlyIsolationWithRequestApproval() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(new SubAgentTaskRequest("检查隔离", "reader", "write", "", Map.of(
                        "agent.isolation", "write",
                        "toolPermissionMode", "never",
                        "approvedToolIds", "builtin.execute.command",
                        "allowHighRiskTools", "true"))),
                false,
                null,
                "fanout",
                Map.of()));

        Map<String, String> metadata = response.tasks().get(0).task().metadata();
        assertEquals("read-only", metadata.get("agent.isolation"));
        assertEquals("write", metadata.get("agent.isolation.requested"));
        assertEquals("read-only", metadata.get("agent.isolation.effective"));
        assertEquals("metadata-read-only", metadata.get("agent.isolation.profile"));
        assertEquals("tool-guard", metadata.get("agent.isolation.enforcement"));
        assertEquals("false", metadata.get("agent.worker.eligible"));
        assertEquals("not-started", metadata.get("agent.worker.mode"));
        assertEquals("false", metadata.get("agent.worker.configured"));
        assertTrue(metadata.get("agent.isolation.overrideReason").contains("降级"));
        assertEquals("ask", metadata.get("toolPermissionMode"));
        assertEquals("never", metadata.get("agent.permissionMode"));
        assertEquals("builtin.execute.command", metadata.get("agent.approvedToolIds"));
        assertEquals("local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement",
                metadata.get("policy.resolutionOrder"));
        assertFalse(metadata.containsKey("approvedToolIds"));
        assertFalse(metadata.containsKey("allowHighRiskTools"));
    }

    @Test
    void childMetadataKeepsAgentScopedPolicyIntent() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        AgentOrchestrationController controller = controller(runtime, taskStore, new InMemoryPlanStore());

        SubAgentBatchTaskResponse response = controller.createSubTasks(parent.id(), new SubAgentBatchTaskRequest(
                List.of(new SubAgentTaskRequest("检查策略", "reader", "", "", Map.of(
                        "agent.permissionMode", "custom",
                        "agent.approvedToolIds", "builtin.filesystem.read_text_file"))),
                false,
                null,
                "fanout",
                Map.of()));
        Map<String, String> metadata = response.tasks().get(0).task().metadata();
        assertEquals("read-only", metadata.get("agent.isolation"));
        assertEquals("ask", metadata.get("toolPermissionMode"));
        assertEquals("custom", metadata.get("agent.permissionMode"));
        assertEquals("builtin.filesystem.read_text_file", metadata.get("agent.approvedToolIds"));
        assertFalse(metadata.containsKey("approvedToolIds"));
    }

    @Test
    void planDispatchRejectsCrossSessionPlan() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        FakeRuntime runtime = new FakeRuntime(taskStore);
        AgentTask parent = parentTask(Map.of());
        taskStore.saveTask(parent);
        PlanDraft plan = planDraft("other-session");
        planStore.savePlan(plan);
        AgentOrchestrationController controller = controller(runtime, taskStore, planStore);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.createSubTasksFromPlan(parent.id(), new SubAgentPlanDispatchRequest(
                        plan.id(), true, null, "fanout", true, "", Map.of())));

        assertTrue(error.getMessage().contains("同一会话"));
    }

    private AgentTask parentTask(Map<String, String> metadata) {
        AgentTask task = new AgentTask(UUID.randomUUID().toString(),
                new AgentRequest("父任务", "session-1", "webui", "console", metadata));
        task.markStatus(TaskStatus.RUNNING);
        return task;
    }

    private AgentOrchestrationController controller(FakeRuntime runtime, InMemoryTaskStore taskStore, InMemoryPlanStore planStore) {
        return controller(runtime, taskStore, planStore, new ClawAgentProperties());
    }

    private AgentOrchestrationController controller(FakeRuntime runtime, InMemoryTaskStore taskStore,
                                                    InMemoryPlanStore planStore, ClawAgentProperties properties) {
        return controller(runtime, taskStore, planStore, properties, new NoopWorkerDispatcher());
    }

    private AgentOrchestrationController controller(FakeRuntime runtime, InMemoryTaskStore taskStore,
                                                    InMemoryPlanStore planStore, ClawAgentProperties properties,
                                                    SubAgentWorkerDispatcher workerDispatcher) {
        return new AgentOrchestrationController(runtime, taskStore, planStore, properties, workerDispatcher);
    }

    private ClawAgentProperties configuredWorkerProperties() {
        ClawAgentProperties properties = new ClawAgentProperties();
        properties.getAgents().getWorker().setEnabled(true);
        properties.getAgents().getWorker().setMode("external-process");
        properties.getAgents().getWorker().setCommand("java");
        properties.getAgents().getWorker().setArgs(List.of("-version"));
        return properties;
    }

    private PlanDraft planDraft(String sessionId) {
        return new PlanDraft(UUID.randomUUID().toString(), sessionId, "", "APPROVED", null, null, 1,
                "开发计划", "完成模块审查", "按步骤拆分子 Agent",
                List.of(
                        new PlanItem(UUID.randomUUID().toString(), 1, "审查 controller", "只读检查 controller 层",
                                List.of("builtin.filesystem.read_text_file"), List.of("src/main/java"), "low", false),
                        new PlanItem(UUID.randomUUID().toString(), 2, "危险步骤", "需要执行脚本验证",
                                List.of("builtin.execute.command"), List.of(), "high", true)),
                List.of(), List.of(), List.of(), Instant.now(), Instant.now());
    }

    private PlanDraft planDraftWithApprovalRequiredMediumStep(String sessionId) {
        return new PlanDraft(UUID.randomUUID().toString(), sessionId, "", "APPROVED", null, null, 1,
                "开发计划", "完成模块审查", "按步骤拆分子 Agent",
                List.of(
                        new PlanItem(UUID.randomUUID().toString(), 1, "安全步骤", "只读检查",
                                List.of("builtin.filesystem.read_text_file"), List.of(), "low", false),
                        new PlanItem(UUID.randomUUID().toString(), 2, "需要审批步骤", "风险非 high 但需要审批",
                                List.of("builtin.execute.command"), List.of(), "medium", true)),
                List.of(), List.of(), List.of(), Instant.now(), Instant.now());
    }

    private PlanDraft planDraftWithAutoDispatchMixedSteps(String sessionId) {
        return new PlanDraft(UUID.randomUUID().toString(), sessionId, "", "APPROVED", null, null, 1,
                "开发计划", "完成模块审查", "按步骤拆分子 Agent",
                List.of(
                        new PlanItem(UUID.randomUUID().toString(), 1, "审查 controller", "只读检查 controller 层",
                                List.of("builtin.filesystem.read_text_file"), List.of("src/main/java"), "low", false),
                        new PlanItem(UUID.randomUUID().toString(), 2, "修改 service", "写入代码修复问题",
                                List.of("builtin.filesystem.write_text_file"), List.of("src/main/java"), "low", false),
                        new PlanItem(UUID.randomUUID().toString(), 3, "执行测试", "运行测试命令",
                                List.of("builtin.execute.command"), List.of(), "medium", false)),
                List.of(), List.of(), List.of(), Instant.now(), Instant.now());
    }

    private static class FakeRuntime implements AgentRuntime {
        private final InMemoryTaskStore taskStore;
        private final List<AgentEvent> events = new ArrayList<>();
        private String failedInput = "";
        private int submitCount;

        private FakeRuntime(InMemoryTaskStore taskStore) {
            this.taskStore = taskStore;
        }

        private void failOnInput(String failedInput) {
            this.failedInput = failedInput;
        }

        @Override
        public AgentResult submit(AgentRequest request) {
            submitCount++;
            if (request.input() != null && request.input().contains(failedInput) && !failedInput.isBlank()) {
                throw new IllegalStateException("模拟失败：" + failedInput);
            }
            AgentTask task = new AgentTask(UUID.randomUUID().toString(), request);
            // 控制器测试只关心派生任务元数据；这里直接完成，避免引入真实模型和工具链。
            task.complete("ok");
            taskStore.saveTask(task);
            return new AgentResult(task.id(), task.finalAnswer(), task.status(), task.sessionId());
        }

        @Override
        public AgentResult submit(AgentRequest request, com.github.clawagent.spi.AgentCallback callback) {
            return submit(request);
        }

        @Override
        public AgentResult submitStream(AgentRequest request, com.github.clawagent.spi.AgentCallback callback,
                                        com.github.clawagent.spi.ChatStreamCallback streamCallback) {
            return submit(request);
        }

        @Override
        public AgentTask cancelTask(String taskId) {
            return getTask(taskId);
        }

        @Override
        public AgentTask approveToolCall(String taskId, String stepId, String toolId) {
            return getTask(taskId);
        }

        @Override
        public AgentTask rejectToolCall(String taskId, String stepId, String toolId, String reason) {
            return getTask(taskId);
        }

        @Override
        public String createSessionId() {
            return "session-1";
        }

        @Override
        public Map<String, Object> clearAllSessions() {
            return Map.of();
        }

        @Override
        public AgentTask getTask(String taskId) {
            return taskStore.findTask(taskId).orElseThrow(() -> new NoSuchElementException(taskId));
        }

        @Override
        public List<AgentStep> getSteps(String taskId) {
            return List.of();
        }

        @Override
        public List<AgentMessage> getTaskMessages(String taskId, int limit) {
            return List.of();
        }

        @Override
        public AgentSession createSession(SessionCreateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentSession getSession(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentSession> listSessions(int limit) {
            return List.of();
        }

        @Override
        public List<AgentTask> getSessionTasks(String sessionId, int limit) {
            return taskStore.findTasksBySession(sessionId, limit);
        }

        @Override
        public List<AgentMessage> getSessionMessages(String sessionId, int limit) {
            return List.of();
        }

        @Override
        public List<AgentMessage> getSessionMessagesBefore(String sessionId, Instant beforeCreatedAt, int limit) {
            return List.of();
        }

        @Override
        public AgentSession summarizeSession(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEvent> getSessionEvents(String sessionId, int limit) {
            return List.of();
        }

        @Override
        public List<AgentEvent> getTaskEvents(String taskId, int limit) {
            return events.stream().limit(limit).toList();
        }

        @Override
        public List<AgentEvent> queryEvents(Instant from, Instant to, String level, String type,
                                            String sessionId, String taskId, int limit) {
            return events.stream().limit(limit).toList();
        }

        @Override
        public void recordTaskEvent(String taskId, String level, String type, String message,
                                    Map<String, String> details) {
            events.add(new AgentEvent(UUID.randomUUID().toString(), "", taskId, level, type, message,
                    details, Instant.now()));
        }

        @Override
        public TokenUsageSummary getSessionTokenUsage(String sessionId, int limit) {
            return emptyTokenUsage("session", sessionId);
        }

        @Override
        public TokenUsageSummary getTaskTokenUsage(String taskId) {
            return emptyTokenUsage("task", taskId);
        }

        private TokenUsageSummary emptyTokenUsage(String scopeType, String scopeId) {
            return new TokenUsageSummary(scopeType, scopeId, 0, 0, 0, 0, Map.of(), Map.of());
        }
    }

    private static class NoopWorkerDispatcher implements SubAgentWorkerDispatcher {
        @Override
        public boolean canDispatch(ClawAgentProperties.SubAgentWorker worker) {
            return false;
        }

        @Override
        public SubAgentWorkerDispatchResult dispatch(AgentTask task, ClawAgentProperties.SubAgentWorker worker) {
            throw new UnsupportedOperationException();
        }
    }

    private static class FakeWorkerDispatcher implements SubAgentWorkerDispatcher {
        private int dispatchCount;

        @Override
        public boolean canDispatch(ClawAgentProperties.SubAgentWorker worker) {
            return worker != null && worker.isEnabled();
        }

        @Override
        public SubAgentWorkerDispatchResult dispatch(AgentTask task, ClawAgentProperties.SubAgentWorker worker) {
            dispatchCount++;
            // 测试只验证 controller 是否切到 worker 路径，不启动真实外部进程。
            return new SubAgentWorkerDispatchResult("worker ok", TaskStatus.COMPLETED, Map.of("worker.fake", "yes"));
        }
    }
}
