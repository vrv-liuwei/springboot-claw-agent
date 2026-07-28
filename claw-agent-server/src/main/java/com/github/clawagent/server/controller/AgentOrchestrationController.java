package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.server.dto.AgentOrchestrationGraphView;
import com.github.clawagent.server.dto.SubAgentBatchTaskRequest;
import com.github.clawagent.server.dto.SubAgentBatchTaskResponse;
import com.github.clawagent.server.dto.SubAgentPlanDispatchRequest;
import com.github.clawagent.server.dto.SubAgentTaskRequest;
import com.github.clawagent.server.dto.SubAgentTaskResponse;
import com.github.clawagent.server.service.SubAgentWorkerDispatchResult;
import com.github.clawagent.server.service.SubAgentWorkerDispatcher;
import com.github.clawagent.server.service.SubAgentWorkerDispatchException;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.spi.PlanStore;
import com.github.clawagent.spi.TaskStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 编排接口。
 * 当前先提供父任务创建只读子任务的能力，不引入独立 worker 调度器。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentOrchestrationController {
    private static final String DEFAULT_ROLE = "subagent";
    private static final String READ_ONLY_ISOLATION = "read-only";
    private static final String ISOLATION_REQUESTED_METADATA = "agent.isolation.requested";
    private static final String ISOLATION_EFFECTIVE_METADATA = "agent.isolation.effective";
    private static final String ISOLATION_PROFILE_METADATA = "agent.isolation.profile";
    private static final String ISOLATION_ENFORCEMENT_METADATA = "agent.isolation.enforcement";
    private static final String WORKER_REQUESTED_METADATA = "agent.worker.requested";
    private static final String WORKER_EFFECTIVE_METADATA = "agent.worker.effective";
    private static final String WORKER_ELIGIBLE_METADATA = "agent.worker.eligible";
    private static final String WORKER_MODE_METADATA = "agent.worker.mode";
    private static final String WORKER_REASON_METADATA = "agent.worker.reason";
    private static final String WORKER_CONFIGURED_METADATA = "agent.worker.configured";
    private static final String WORKER_MAX_CONCURRENT_METADATA = "agent.worker.maxConcurrent";
    private static final String WORKER_ACQUIRE_TIMEOUT_METADATA = "agent.worker.acquireTimeoutMs";
    private static final int MAX_GRAPH_NODES = 500;
    private static final int MAX_BATCH_SUBTASKS = 20;
    private static final int DEFAULT_PARALLELISM = 4;
    private static final int MAX_PARALLELISM = 8;

    private final AgentRuntime runtime;
    private final TaskStore taskStore;
    private final PlanStore planStore;
    private final ClawAgentProperties properties;
    private final SubAgentWorkerDispatcher workerDispatcher;

    public AgentOrchestrationController(AgentRuntime runtime,
                                        @Qualifier("taskStore") TaskStore taskStore,
                                        @Qualifier("planStore") PlanStore planStore,
                                        ClawAgentProperties properties,
                                        SubAgentWorkerDispatcher workerDispatcher) {
        this.runtime = runtime;
        this.taskStore = taskStore;
        this.planStore = planStore;
        this.properties = properties == null ? new ClawAgentProperties() : properties;
        this.workerDispatcher = workerDispatcher;
    }

    /**
     * 从父任务派生一个只读子 Agent 任务。
     * 子任务继承项目上下文，但不继承高危工具批准，避免父任务授权被横向扩散。
     */
    @PostMapping("/{parentTaskId}/subtasks")
    public SubAgentTaskResponse createSubTask(@PathVariable String parentTaskId,
                                              @RequestBody SubAgentTaskRequest request) {
        AgentTask parent = runtime.getTask(parentTaskId);
        return submitSubTask(parent, request, "manual", "", 0, 1, false, 1);
    }

    /**
     * 批量派发只读子 Agent 任务。
     * parallel=true 时多个子任务会并行提交给 Runtime；每个子任务仍独立落库和执行。
     */
    @PostMapping("/{parentTaskId}/subtasks/batch")
    public SubAgentBatchTaskResponse createSubTasks(@PathVariable String parentTaskId,
                                                    @RequestBody SubAgentBatchTaskRequest request) {
        AgentTask parent = runtime.getTask(parentTaskId);
        List<SubAgentTaskRequest> tasks = safeBatchTasks(request);
        tasks = tasks.stream().map(task -> mergeBatchMetadata(task, request.metadata())).toList();
        return dispatchSubTasks(parent, tasks, Boolean.TRUE.equals(request.parallel()),
                safeParallelism(request.maxParallelism(), tasks.size()), normalize(request.strategy(), "manual"));
    }

    /**
     * 按计划项自动拆分并派发只读子 Agent。
     * 这里复用 PlanDraft 的步骤，不再维护第二套任务拆分模型。
     */
    @PostMapping("/{parentTaskId}/subtasks/from-plan")
    public SubAgentBatchTaskResponse createSubTasksFromPlan(@PathVariable String parentTaskId,
                                                            @RequestBody SubAgentPlanDispatchRequest request) {
        AgentTask parent = runtime.getTask(parentTaskId);
        PlanDraft plan = loadPlan(request == null ? "" : request.planId());
        ensurePlanBelongsToParentSession(parent, plan);
        List<SubAgentTaskRequest> tasks = planItemsToSubTasks(plan, request);
        return dispatchSubTasks(parent, tasks, request != null && Boolean.TRUE.equals(request.parallel()),
                safeParallelism(request == null ? null : request.maxParallelism(), tasks.size()),
                normalize(request == null ? "" : request.strategy(), "plan-items"));
    }

    private SubAgentBatchTaskResponse dispatchSubTasks(AgentTask parent,
                                                       List<SubAgentTaskRequest> tasks,
                                                       boolean parallel,
                                                       int maxParallelism,
                                                       String strategy) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("批量子 Agent 任务没有有效输入");
        }
        List<SubAgentTaskRequest> dispatchTasks = List.copyOf(tasks);
        String dispatchId = UUID.randomUUID().toString();

        List<SubTaskDispatchOutcome> outcomes;
        if (parallel) {
            // 并行派发只负责调度多个子任务，权限隔离仍由每个子任务 metadata 和 ToolExecutionGuard 执行。
            ExecutorService executor = Executors.newFixedThreadPool(maxParallelism, subAgentThreadFactory(dispatchId));
            List<CompletableFuture<SubTaskDispatchOutcome>> futures = new ArrayList<>();
            try {
                for (int index = 0; index < dispatchTasks.size(); index++) {
                    int taskIndex = index;
                    futures.add(CompletableFuture.supplyAsync(() ->
                            submitSubTaskSafely(parent, dispatchTasks.get(taskIndex), strategy, dispatchId,
                                    taskIndex, dispatchTasks.size(), true, maxParallelism), executor));
                }
                outcomes = futures.stream().map(CompletableFuture::join).toList();
            } finally {
                executor.shutdownNow();
            }
        } else {
            outcomes = new ArrayList<>();
            for (int index = 0; index < dispatchTasks.size(); index++) {
                outcomes.add(submitSubTaskSafely(parent, dispatchTasks.get(index), strategy, dispatchId,
                        index, dispatchTasks.size(), false, 1));
            }
        }

        List<SubAgentTaskResponse> responses = outcomes.stream()
                .filter(outcome -> outcome.response() != null)
                .map(SubTaskDispatchOutcome::response)
                .toList();
        Map<Integer, String> errors = new LinkedHashMap<>();
        outcomes.stream()
                .filter(outcome -> outcome.error() != null && !outcome.error().isBlank())
                .forEach(outcome -> errors.put(outcome.index(), outcome.error()));
        int effectiveParallelism = parallel ? maxParallelism : 1;
        recordBatchDispatchEvent(parent, dispatchId, strategy, parallel, effectiveParallelism,
                dispatchTasks.size(), responses.size(), errors.size());
        return new SubAgentBatchTaskResponse(parent.id(), dispatchId, strategy, parallel,
                effectiveParallelism, dispatchTasks.size(), responses.size(), errors.size(), responses, errors);
    }

    /**
     * 查询某个父任务派生出的子 Agent 任务。
     */
    @GetMapping("/{parentTaskId}/subtasks")
    public List<AgentTask> subTasks(@PathVariable String parentTaskId,
                                    @RequestParam(name = "limit", defaultValue = "100") int limit) {
        runtime.getTask(parentTaskId);
        return taskStore.findSubTasks(parentTaskId, safeLimit(limit));
    }

    /**
     * 查询父子 Agent 编排图。
     * 图结构从任务 metadata 里的 agent.parentTaskId 派生，便于前端展示当前任务拆分状态。
     */
    @GetMapping("/{rootTaskId}/graph")
    public AgentOrchestrationGraphView graph(@PathVariable String rootTaskId,
                                             @RequestParam(name = "depth", defaultValue = "3") int depth) {
        AgentTask root = runtime.getTask(rootTaskId);
        int maxDepth = Math.min(Math.max(depth, 1), 8);
        List<AgentOrchestrationGraphView.Node> nodes = new ArrayList<>();
        List<AgentOrchestrationGraphView.Edge> edges = new ArrayList<>();
        Queue<TaskDepth> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        boolean truncated = false;

        queue.add(new TaskDepth(root, 0));
        while (!queue.isEmpty()) {
            TaskDepth current = queue.poll();
            AgentTask task = current.task();
            if (task == null || !visited.add(task.id())) {
                continue;
            }
            nodes.add(toNode(task, current.depth()));
            if (nodes.size() >= MAX_GRAPH_NODES) {
                truncated = true;
                break;
            }
            if (current.depth() >= maxDepth) {
                continue;
            }
            // 子任务查询复用 TaskStore，避免维护第二份编排关系表。
            for (AgentTask child : taskStore.findSubTasks(task.id(), MAX_GRAPH_NODES)) {
                edges.add(new AgentOrchestrationGraphView.Edge(
                        task.id(),
                        child.id(),
                        normalize(child.metadata().get("agent.role"), DEFAULT_ROLE),
                        normalize(child.metadata().get("agent.isolation"), READ_ONLY_ISOLATION)));
                queue.add(new TaskDepth(child, current.depth() + 1));
            }
        }
        return toGraph(rootTaskId, maxDepth, truncated, nodes, edges);
    }

    private SubAgentTaskResponse submitSubTask(AgentTask parent, SubAgentTaskRequest request,
                                               String strategy, String dispatchId, int index, int total,
                                               boolean parallel, int maxParallelism) {
        String role = normalize(request.role(), DEFAULT_ROLE);
        Map<String, String> metadata = buildChildMetadata(parent, request, role);
        applySplitStrategyMetadata(metadata, strategy, role, parallel, maxParallelism);
        metadata.put("agent.dispatch.index", String.valueOf(index));
        metadata.put("agent.dispatch.total", String.valueOf(total));
        if (dispatchId != null && !dispatchId.isBlank()) {
            metadata.put("agent.dispatchId", dispatchId);
        }
        boolean dispatchWithWorker = shouldDispatchWithWorker(metadata);
        if (dispatchWithWorker) {
            activateWorkerMetadata(metadata);
        }
        AgentRequest childRequest = new AgentRequest(
                decorateInput(request.input(), role),
                parent.sessionId(),
                normalize(parent.channelId(), "webui"),
                normalize(parent.userId(), "console"),
                metadata);

        if (dispatchWithWorker) {
            AgentResult result = submitSubTaskViaWorker(childRequest);
            AgentTask child = runtime.getTask(result.taskId());
            recordSubTaskEvent(parent, child, role);
            return new SubAgentTaskResponse(parent.id(), child.id(), role, READ_ONLY_ISOLATION, result, child);
        }

        AgentResult result = runtime.submit(childRequest);
        AgentTask child = runtime.getTask(result.taskId());
        recordSubTaskEvent(parent, child, role);
        return new SubAgentTaskResponse(parent.id(), child.id(), role, READ_ONLY_ISOLATION, result, child);
    }

    private AgentResult submitSubTaskViaWorker(AgentRequest childRequest) {
        AgentTask child = new AgentTask(UUID.randomUUID().toString(), childRequest);
        child.markStatus(TaskStatus.RUNNING);
        taskStore.saveTask(child);
        runtime.recordTaskEvent(child.id(), "INFO", "agent.worker.started", "子 Agent worker 进程启动", Map.of(
                "workerMode", child.metadata().getOrDefault(WORKER_MODE_METADATA, "external-process")));
        try {
            SubAgentWorkerDispatchResult workerResult = workerDispatcher.dispatch(child, properties.getAgents().getWorker());
            if (workerResult.metadata() != null) {
                child.metadata().putAll(workerResult.metadata());
            }
            TaskStatus status = workerResult.status() == null ? TaskStatus.COMPLETED : workerResult.status();
            if (status == TaskStatus.COMPLETED) {
                child.complete(normalize(workerResult.answer(), ""));
            } else if (status == TaskStatus.CONTINUATION_REQUIRED) {
                child.requireContinuation(normalize(workerResult.answer(), ""));
            } else {
                child.markStatus(status);
            }
            taskStore.updateTask(child);
            runtime.recordTaskEvent(child.id(), "INFO", "agent.worker.completed", "子 Agent worker 执行完成", Map.of(
                    "status", child.status().name()));
            return new AgentResult(child.id(), normalize(workerResult.answer(), ""), child.status(), child.sessionId());
        } catch (Exception e) {
            if (e instanceof SubAgentWorkerDispatchException workerError) {
                child.metadata().putAll(workerError.metadata());
            }
            child.markStatus(TaskStatus.FAILED);
            taskStore.updateTask(child);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("error", message);
            details.put("workerPid", child.metadata().getOrDefault("agent.worker.pid", ""));
            details.put("elapsedMs", child.metadata().getOrDefault("agent.worker.elapsedMs", ""));
            details.put("timedOut", child.metadata().getOrDefault("agent.worker.timedOut", ""));
            runtime.recordTaskEvent(child.id(), "ERROR", "agent.worker.failed", "子 Agent worker 执行失败", details);
            return new AgentResult(child.id(), "子 Agent worker 执行失败：" + message, child.status(), child.sessionId());
        }
    }

    private SubTaskDispatchOutcome submitSubTaskSafely(AgentTask parent, SubAgentTaskRequest request,
                                                       String strategy, String dispatchId, int index, int total,
                                                       boolean parallel, int maxParallelism) {
        try {
            return new SubTaskDispatchOutcome(index,
                    submitSubTask(parent, request, strategy, dispatchId, index, total, parallel, maxParallelism), "");
        } catch (Exception e) {
            return new SubTaskDispatchOutcome(index, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private List<SubAgentTaskRequest> safeBatchTasks(SubAgentBatchTaskRequest request) {
        if (request == null || request.tasks() == null || request.tasks().isEmpty()) {
            throw new IllegalArgumentException("批量子 Agent 任务不能为空");
        }
        if (request.tasks().size() > MAX_BATCH_SUBTASKS) {
            throw new IllegalArgumentException("批量子 Agent 任务不能超过 " + MAX_BATCH_SUBTASKS + " 个");
        }
        List<SubAgentTaskRequest> tasks = request.tasks().stream()
                .filter(task -> task != null && task.input() != null && !task.input().isBlank())
                .toList();
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("批量子 Agent 任务没有有效输入");
        }
        return tasks;
    }

    private int safeParallelism(Integer requested, int taskCount) {
        if (taskCount <= 1) {
            return 1;
        }
        int value = requested == null || requested <= 0 ? DEFAULT_PARALLELISM : requested;
        return Math.min(Math.min(value, MAX_PARALLELISM), taskCount);
    }

    private PlanDraft loadPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("planId 不能为空");
        }
        return planStore.findPlan(planId).orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));
    }

    private void ensurePlanBelongsToParentSession(AgentTask parent, PlanDraft plan) {
        if (plan.sessionId() != null && !plan.sessionId().isBlank() && parent.sessionId() != null
                && !parent.sessionId().isBlank() && !plan.sessionId().equals(parent.sessionId())) {
            throw new IllegalArgumentException("计划和父任务不属于同一会话，不能派发子 Agent");
        }
    }

    private List<SubAgentTaskRequest> planItemsToSubTasks(PlanDraft plan, SubAgentPlanDispatchRequest request) {
        boolean includeHighRisk = request == null || !Boolean.FALSE.equals(request.includeHighRisk());
        String dispatchMode = normalize(request == null ? "" : request.dispatchMode(), "manual").toLowerCase(Locale.ROOT);
        boolean autoDispatch = "auto".equals(dispatchMode);
        Map<String, String> requestMetadata = request == null || request.metadata() == null ? Map.of() : request.metadata();
        List<SubAgentTaskRequest> tasks = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            // 不显式允许高危时，既跳过 high 风险，也跳过任何需要审批的计划项。
            if (!includeHighRisk && isBlockedForSafeDispatch(item)) {
                continue;
            }
            // auto 模式只挑选明确适合只读分析的计划项，避免模型把写入或执行步骤盲目拆给子 Agent。
            if (autoDispatch && !isAutoDispatchCandidate(item)) {
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(requestMetadata);
            metadata.put("agent.split.source", "plan");
            metadata.put("agent.split.dispatchMode", dispatchMode);
            metadata.put("plan.id", plan.id());
            metadata.put("plan.version", String.valueOf(plan.version()));
            metadata.put("plan.itemId", item.id());
            metadata.put("plan.itemOrder", String.valueOf(item.itemOrder()));
            metadata.put("plan.itemRiskLevel", item.riskLevel());
            metadata.put("plan.itemRequiresApproval", String.valueOf(item.requiresApproval()));
            metadata.put("agent.split.highRiskPolicy", includeHighRisk ? "include-all" : "skip-high-or-approval");
            if (autoDispatch) {
                metadata.put("agent.split.autoCandidate", "true");
            }
            tasks.add(new SubAgentTaskRequest(planItemInput(plan, item), "plan-step-" + item.itemOrder(),
                    READ_ONLY_ISOLATION, "", metadata));
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("计划没有可派发的子 Agent 步骤");
        }
        if (tasks.size() > MAX_BATCH_SUBTASKS) {
            throw new IllegalArgumentException("计划步骤不能超过 " + MAX_BATCH_SUBTASKS + " 个");
        }
        return tasks;
    }

    private boolean isBlockedForSafeDispatch(PlanItem item) {
        if (item == null) {
            return true;
        }
        return "high".equalsIgnoreCase(item.riskLevel()) || item.requiresApproval();
    }

    private boolean isAutoDispatchCandidate(PlanItem item) {
        if (isBlockedForSafeDispatch(item) || !"low".equalsIgnoreCase(item.riskLevel())) {
            return false;
        }
        if (hasUnsafeExpectedTool(item) || containsRiskyActionText(item)) {
            return false;
        }
        return hasReadOnlySignal(item);
    }

    private boolean hasUnsafeExpectedTool(PlanItem item) {
        for (String tool : item.expectedTools()) {
            String value = normalize(tool, "").toLowerCase(Locale.ROOT);
            if (value.contains("execute")
                    || value.contains("process")
                    || value.contains("write")
                    || value.contains("edit")
                    || value.contains("delete")
                    || value.contains("install")
                    || value.contains("commit")
                    || value.contains("apply_patch")
                    || value.contains("shell")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRiskyActionText(PlanItem item) {
        String text = (normalize(item.title(), "") + " " + normalize(item.description(), "")).toLowerCase(Locale.ROOT);
        List<String> riskyWords = List.of(
                "修改", "写入", "删除", "安装", "执行", "启动", "部署", "提交", "回滚", "覆盖", "生成", "创建", "修复",
                "write", "delete", "install", "execute", "start", "deploy", "commit", "rollback", "patch", "edit");
        return riskyWords.stream().anyMatch(text::contains);
    }

    private boolean hasReadOnlySignal(PlanItem item) {
        for (String tool : item.expectedTools()) {
            String value = normalize(tool, "").toLowerCase(Locale.ROOT);
            if (value.contains("read")
                    || value.contains("list")
                    || value.contains("search")
                    || value.contains("grep")
                    || value.contains("rg")
                    || value.contains("status")
                    || value.contains("diff")) {
                return true;
            }
        }
        String text = (normalize(item.title(), "") + " " + normalize(item.description(), "")).toLowerCase(Locale.ROOT);
        List<String> readOnlyWords = List.of(
                "审查", "分析", "检查", "搜索", "定位", "阅读", "调研", "总结", "梳理", "review", "analyze", "inspect", "search");
        return readOnlyWords.stream().anyMatch(text::contains);
    }

    private String planItemInput(PlanDraft plan, PlanItem item) {
        StringBuilder input = new StringBuilder();
        input.append("基于已生成计划执行只读子 Agent 分析，不要修改文件，不要执行写入或高危命令。\n");
        input.append("计划ID=").append(plan.id()).append("，版本=").append(plan.version()).append("。\n");
        input.append("总体目标：").append(normalize(plan.goal(), "")).append("\n");
        input.append("当前计划步骤 ").append(item.itemOrder()).append("：").append(normalize(item.title(), "")).append("\n");
        input.append("步骤说明：").append(normalize(item.description(), "")).append("\n");
        if (!item.expectedTools().isEmpty()) {
            input.append("预计工具：").append(String.join(", ", item.expectedTools())).append("\n");
        }
        if (!item.expectedFileChanges().isEmpty()) {
            input.append("预计涉及文件：").append(String.join(", ", item.expectedFileChanges())).append("\n");
        }
        input.append("只输出本步骤的发现、风险和建议，不要直接修改项目。");
        return input.toString();
    }

    private SubAgentTaskRequest mergeBatchMetadata(SubAgentTaskRequest task, Map<String, String> batchMetadata) {
        if (batchMetadata == null || batchMetadata.isEmpty()) {
            return task;
        }
        Map<String, String> metadata = new LinkedHashMap<>(batchMetadata);
        if (task.metadata() != null) {
            metadata.putAll(task.metadata());
        }
        return new SubAgentTaskRequest(task.input(), task.role(), task.isolation(), task.workerMode(), metadata);
    }

    private Map<String, String> buildChildMetadata(AgentTask parent, SubAgentTaskRequest request, String role) {
        Map<String, String> metadata = new LinkedHashMap<>();
        copyIfPresent(parent.metadata(), metadata,
                "workspaceId", "workspace.id", "workspaceName", "workspace.name",
                "workspaceRoot", "workspace.root", "workspace.projectPath",
                "projectPath", "activeProjectPath", "cwd",
                "knowledge.enabled", "knowledge.documentIds", "knowledge.scope", "knowledge.intent",
                "attachmentIds", "attachmentKnowledgeDocumentIds", "attachments");
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        preserveRequestedAgentPolicy(metadata);
        metadata.put("agent.kind", "subagent");
        metadata.put("agent.parentTaskId", parent.id());
        metadata.put("agent.rootTaskId", normalize(parent.metadata().get("agent.rootTaskId"), parent.id()));
        metadata.put("agent.role", role);
        String requestedIsolation = firstNonBlank(request.isolation(), metadata.get("agent.isolation"), READ_ONLY_ISOLATION);
        applyIsolationMetadata(metadata, requestedIsolation);
        String requestedWorkerMode = firstNonBlank(request.workerMode(),
                firstNonBlank(metadata.get(WORKER_REQUESTED_METADATA),
                        firstNonBlank(metadata.get(WORKER_MODE_METADATA), workerModeFromIsolation(requestedIsolation))));
        applyWorkerMetadata(metadata, requestedWorkerMode);
        // 子 Agent 当前仍强制只读执行；请求侧的权限意图只保留在 agent.* 字段，不能直接放宽本次有效策略。
        metadata.put("toolPermissionMode", "ask");
        metadata.put("policy.approval.source", "agent-isolation:" + READ_ONLY_ISOLATION);
        metadata.put("policy.approval.scope", "agent");
        metadata.put("policy.resolutionOrder", "local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement");
        metadata.put("policy.overrideReason", "只读子 Agent 不继承父任务高危批准。");
        metadata.remove("approvedToolIds");
        metadata.remove("allowHighRiskTools");
        metadata.remove("approvalMode");
        metadata.remove("permissionMode");
        return metadata;
    }

    private void applyIsolationMetadata(Map<String, String> metadata, String requestedIsolation) {
        String requested = normalize(requestedIsolation, READ_ONLY_ISOLATION);
        // 当前子 Agent 的隔离由 ToolExecutionGuard 和 metadata 共同约束；先把请求值和实际值都落库，方便后续接入独立 worker。
        metadata.put(ISOLATION_REQUESTED_METADATA, requested);
        metadata.put(ISOLATION_EFFECTIVE_METADATA, READ_ONLY_ISOLATION);
        metadata.put(ISOLATION_PROFILE_METADATA, "metadata-read-only");
        metadata.put(ISOLATION_ENFORCEMENT_METADATA, "tool-guard");
        metadata.put(WORKER_ELIGIBLE_METADATA, "false");
        metadata.put(WORKER_MODE_METADATA, "not-started");
        metadata.put("agent.isolation", READ_ONLY_ISOLATION);
        if (!READ_ONLY_ISOLATION.equalsIgnoreCase(requested)) {
            metadata.put("agent.isolation.overrideReason", "当前子 Agent 只支持只读隔离，请求隔离级别已降级。");
        } else {
            metadata.remove("agent.isolation.overrideReason");
        }
    }

    private void applyWorkerMetadata(Map<String, String> metadata, String requestedWorkerMode) {
        String requested = normalize(requestedWorkerMode, "none");
        boolean workerRequested = isWorkerRequested(requested);
        ClawAgentProperties.SubAgentWorker worker = properties.getAgents().getWorker();
        // 子 Agent worker 先按配置做能力判定；真正进程 dispatcher 未接入前，effective 必须保持 not-started。
        metadata.put(WORKER_REQUESTED_METADATA, requested);
        metadata.put(WORKER_EFFECTIVE_METADATA, "not-started");
        metadata.put(WORKER_ELIGIBLE_METADATA, "false");
        metadata.put(WORKER_MODE_METADATA, "not-started");
        metadata.put(WORKER_CONFIGURED_METADATA, "false");
        metadata.remove(WORKER_MAX_CONCURRENT_METADATA);
        metadata.remove(WORKER_ACQUIRE_TIMEOUT_METADATA);
        if (workerRequested) {
            if (!worker.isEnabled()) {
                metadata.put(WORKER_REASON_METADATA, "子 Agent worker 未启用，当前降级为只读 tool-guard 隔离。");
            } else if (!isProcessWorkerMode(worker.getMode())) {
                metadata.put(WORKER_REASON_METADATA, "子 Agent worker 模式不支持：" + worker.getMode() + "，当前降级为只读 tool-guard 隔离。");
            } else if (worker.getCommand().isBlank()) {
                metadata.put(WORKER_REASON_METADATA, "子 Agent worker 已启用但未配置启动命令，当前降级为只读 tool-guard 隔离。");
            } else {
                metadata.put(WORKER_CONFIGURED_METADATA, "true");
                metadata.put(WORKER_ELIGIBLE_METADATA, "true");
                metadata.put(WORKER_MODE_METADATA, normalize(worker.getMode(), "external-process"));
                metadata.put(WORKER_MAX_CONCURRENT_METADATA, String.valueOf(worker.getMaxConcurrent()));
                metadata.put(WORKER_ACQUIRE_TIMEOUT_METADATA, String.valueOf(worker.getAcquireTimeoutMs()));
                metadata.put(WORKER_REASON_METADATA, "子 Agent Runtime 独立 worker 已配置，但进程 dispatcher 尚未接入，当前仍降级为只读 tool-guard 隔离。");
            }
        } else {
            metadata.remove(WORKER_REASON_METADATA);
        }
    }

    private boolean shouldDispatchWithWorker(Map<String, String> metadata) {
        return "true".equalsIgnoreCase(metadata.get(WORKER_ELIGIBLE_METADATA))
                && isWorkerRequested(metadata.get(WORKER_REQUESTED_METADATA))
                && workerDispatcher != null
                && workerDispatcher.canDispatch(properties.getAgents().getWorker());
    }

    private void activateWorkerMetadata(Map<String, String> metadata) {
        // worker 真正接管执行时，审计字段必须从“可配置”推进到“已生效”。
        metadata.put(WORKER_EFFECTIVE_METADATA, "external-process");
        metadata.put(WORKER_MODE_METADATA, normalize(properties.getAgents().getWorker().getMode(), "external-process"));
        metadata.put(WORKER_REASON_METADATA, "子 Agent Runtime 由 external-process worker 执行。");
        metadata.put(ISOLATION_ENFORCEMENT_METADATA, "worker-process+tool-guard");
    }

    private boolean isProcessWorkerMode(String mode) {
        String normalized = normalize(mode, "external-process");
        return "process".equalsIgnoreCase(normalized)
                || "external-process".equalsIgnoreCase(normalized)
                || "process-worker".equalsIgnoreCase(normalized)
                || "isolated-worker".equalsIgnoreCase(normalized);
    }

    private String workerModeFromIsolation(String isolation) {
        String normalized = normalize(isolation, "");
        if ("worker".equalsIgnoreCase(normalized)
                || "process".equalsIgnoreCase(normalized)
                || "process-worker".equalsIgnoreCase(normalized)
                || "isolated-worker".equalsIgnoreCase(normalized)) {
            return "process";
        }
        return "none";
    }

    private boolean isWorkerRequested(String mode) {
        String normalized = normalize(mode, "none");
        return !normalized.isBlank()
                && !"none".equalsIgnoreCase(normalized)
                && !"false".equalsIgnoreCase(normalized)
                && !"not-started".equalsIgnoreCase(normalized);
    }

    private void applySplitStrategyMetadata(Map<String, String> metadata, String strategy, String role,
                                            boolean parallel, int maxParallelism) {
        String normalizedStrategy = normalize(strategy, "manual");
        // 拆分策略只记录调度意图和审计信息，不在这里放宽子 Agent 的只读权限。
        metadata.put("agent.dispatch.strategy", normalizedStrategy);
        metadata.put("agent.dispatch.parallel", String.valueOf(parallel));
        metadata.put("agent.dispatch.maxParallelism", String.valueOf(parallel ? maxParallelism : 1));
        metadata.put("agent.split.strategy", normalizedStrategy);
        metadata.put("agent.split.profile", strategyProfile(normalizedStrategy));
        metadata.put("agent.split.rolePolicy", DEFAULT_ROLE.equals(role) ? "default-role" : "request-role");
        metadata.putIfAbsent("agent.split.source", "manual");
    }

    private String strategyProfile(String strategy) {
        String normalized = normalize(strategy, "manual").toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "manual", "fanout", "plan-items", "parallel-check", "review", "verify", "research" -> normalized;
            default -> "custom";
        };
    }

    private void preserveRequestedAgentPolicy(Map<String, String> metadata) {
        String requestedMode = firstNonBlank(
                metadata.get("agent.permissionMode"),
                metadata.get("agent.approvalMode"),
                metadata.get("toolPermissionMode"),
                metadata.get("approvalMode"),
                metadata.get("permissionMode"));
        if (!requestedMode.isBlank()) {
            metadata.put("agent.permissionMode", requestedMode);
        }
        String requestedToolIds = firstNonBlank(
                metadata.get("agent.approvedToolIds"),
                metadata.get("agent.allowedToolIds"),
                metadata.get("approvedToolIds"));
        if (!requestedToolIds.isBlank()) {
            metadata.put("agent.approvedToolIds", requestedToolIds);
        }
    }

    private void copyIfPresent(Map<String, String> source, Map<String, String> target, String... keys) {
        for (String key : keys) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                target.put(key, value);
            }
        }
    }

    private void recordSubTaskEvent(AgentTask parent, AgentTask child, String role) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("childTaskId", child.id());
        details.put("role", role);
        details.put("isolation", READ_ONLY_ISOLATION);
        runtime.recordTaskEvent(parent.id(), "INFO", "agent.subtask_created", "子 Agent 任务已创建", details);
    }

    private void recordBatchDispatchEvent(AgentTask parent, String dispatchId, String strategy, boolean parallel,
                                          int maxParallelism,
                                          int total, int succeeded, int failed) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("dispatchId", dispatchId);
        details.put("strategy", strategy);
        details.put("parallel", String.valueOf(parallel));
        details.put("maxParallelism", String.valueOf(maxParallelism));
        details.put("total", String.valueOf(total));
        details.put("succeeded", String.valueOf(succeeded));
        details.put("failed", String.valueOf(failed));
        runtime.recordTaskEvent(parent.id(), "INFO", "agent.subtasks_dispatched", "子 Agent 批量派发完成", details);
    }

    private ThreadFactory subAgentThreadFactory(String dispatchId) {
        AtomicInteger counter = new AtomicInteger(1);
        String normalizedDispatchId = normalize(dispatchId, "dispatch");
        String threadId = normalizedDispatchId.length() <= 8 ? normalizedDispatchId : normalizedDispatchId.substring(0, 8);
        String prefix = "claw-subagent-" + threadId + "-";
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String decorateInput(String input, String role) {
        return "[子 Agent role=" + role + " isolation=" + READ_ONLY_ISOLATION + "]\n" + normalize(input, "");
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }

    private AgentOrchestrationGraphView.Node toNode(AgentTask task, int depth) {
        return new AgentOrchestrationGraphView.Node(
                task.id(),
                task.metadata().get("agent.parentTaskId"),
                normalize(task.metadata().get("agent.role"), depth == 0 ? "root" : DEFAULT_ROLE),
                normalize(task.metadata().get("agent.isolation"), depth == 0 ? "root" : READ_ONLY_ISOLATION),
                task.status(),
                task.input(),
                depth,
                task.createdAt(),
                task.updatedAt());
    }

    private AgentOrchestrationGraphView toGraph(String rootTaskId,
                                                int maxDepth,
                                                boolean truncated,
                                                List<AgentOrchestrationGraphView.Node> nodes,
                                                List<AgentOrchestrationGraphView.Edge> edges) {
        long running = nodes.stream().filter(node -> node.status() != null
                && ("RUNNING".equals(node.status().name()) || "PENDING".equals(node.status().name()))).count();
        long waiting = nodes.stream().filter(node -> node.status() != null
                && ("WAITING_APPROVAL".equals(node.status().name()) || "CONTINUATION_REQUIRED".equals(node.status().name()))).count();
        long completed = nodes.stream().filter(node -> node.status() != null && "COMPLETED".equals(node.status().name())).count();
        long failed = nodes.stream().filter(node -> node.status() != null
                && ("FAILED".equals(node.status().name()) || "CANCELLED".equals(node.status().name()))).count();
        return new AgentOrchestrationGraphView(
                rootTaskId,
                nodes.size(),
                (int) running,
                (int) waiting,
                (int) completed,
                (int) failed,
                maxDepth,
                truncated,
                List.copyOf(nodes),
                List.copyOf(edges));
    }

    private record TaskDepth(AgentTask task, int depth) {
    }

    private record SubTaskDispatchOutcome(int index, SubAgentTaskResponse response, String error) {
    }
}
