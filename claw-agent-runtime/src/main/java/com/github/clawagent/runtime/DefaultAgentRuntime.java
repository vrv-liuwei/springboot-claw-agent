package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AgentPlan;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.AgentReActPlanner;
import com.github.clawagent.spi.AgentResponseGenerator;
import com.github.clawagent.spi.AgentRuntimeInterceptor;
import com.github.clawagent.spi.AgentRuntimeInterceptorContext;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.LlmCallTrace;
import com.github.clawagent.spi.LlmTraceContext;
import com.github.clawagent.spi.MemoryPromoter;
import com.github.clawagent.spi.ChatStreamCallback;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.SessionSummarizer;
import com.github.clawagent.spi.StreamingAgentResponseGenerator;
import com.github.clawagent.spi.TaskStore;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ToolExecutionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DefaultAgentRuntime 是 M1 的核心执行链路。
 * 这里刻意把“规划、工具查找、步骤落库、错误处理”显式拆开，便于后续替换为 LLM Planner 和审批流。
 */
public class DefaultAgentRuntime implements AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);
    /** 默认 ReAct 最大轮次，避免模型反复规划导致请求长时间不返回。 */
    private static final int DEFAULT_MAX_REACT_ROUNDS = 20;
    /** 传给模型的最近会话消息数量，避免短句追问丢失上下文。 */
    private static final int SESSION_CONTEXT_MESSAGE_LIMIT = 20;
    /** 会话上下文最大字符数，避免历史消息无限挤占工具列表和模型回复空间。 */
    private static final int SESSION_CONTEXT_CHAR_LIMIT = 10_000;
    /** Runtime 注入给 Planner/ResponseGenerator 的会话上下文 metadata key。 */
    private static final String SESSION_CONTEXT_METADATA_KEY = "runtime.sessionContext";
    /** JDK17 下平台线程仍然较重，先限制单轮只读工具并发数，避免一次 ReAct 打满服务器线程。 */
    private static final int MAX_PARALLEL_TOOL_CALLS = 8;

    private final AgentPlanner planner;
    private final AgentResponseGenerator responseGenerator;
    private final AgentToolRegistry toolRegistry;
    private final TaskStore taskStore;
    private final SessionStore sessionStore;
    private final SessionMessageStore messageStore;
    private final SessionSummarizer sessionSummarizer;
    private final List<MemoryPromoter> memoryPromoters;
    private final AgentEventStore eventStore;
    private final TodoStore todoStore;
    private final List<ToolExecutionGuard> toolGuards;
    private final List<AgentCallback> callbacks;
    /** 已收到取消请求的任务 ID。运行线程会在规划、工具和最终回复边界协作式检查。 */
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    private final int maxReactRounds;
    /** Runtime 横切拦截器链，按 order 顺序处理脱敏、审计规范化、合规过滤等扩展逻辑。 */
    private final List<AgentRuntimeInterceptor> runtimeInterceptors;
    /** 当前提交请求的临时回调列表，用于单次任务流式推送运行事件。 */
    private final ThreadLocal<List<AgentCallback>> activeCallbacks = new ThreadLocal<>();
    /** 当前正在执行的 Todo，用于把后续工具调用日志挂到具体 Todo 上。 */
    private final ThreadLocal<TodoItem> activeTodo = new ThreadLocal<>();

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer, memoryPromoters, eventStore, null, toolGuards, callbacks, DEFAULT_MAX_REACT_ROUNDS);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer, memoryPromoters, eventStore, todoStore, toolGuards, callbacks, DEFAULT_MAX_REACT_ROUNDS);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer, memoryPromoters, eventStore, todoStore, toolGuards, callbacks, maxReactRounds, List.of(new SensitiveDataInterceptor(SanitizationOptions.defaults())));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, SanitizationOptions sanitizationOptions) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer, memoryPromoters, eventStore, todoStore, toolGuards, callbacks, maxReactRounds, List.of(new SensitiveDataInterceptor(sanitizationOptions)));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors) {
        this.planner = planner;
        this.responseGenerator = responseGenerator;
        this.toolRegistry = toolRegistry;
        this.taskStore = taskStore;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.sessionSummarizer = sessionSummarizer;
        this.memoryPromoters = memoryPromoters == null ? List.of() : List.copyOf(memoryPromoters);
        this.eventStore = eventStore;
        this.todoStore = todoStore;
        this.toolGuards = toolGuards == null ? List.of() : List.copyOf(toolGuards);
        this.callbacks = new ArrayList<>(callbacks);
        this.maxReactRounds = Math.max(1, maxReactRounds);
        this.runtimeInterceptors = runtimeInterceptors == null
                ? List.of()
                : runtimeInterceptors.stream()
                .filter(interceptor -> interceptor != null)
                .sorted(Comparator.comparingInt(AgentRuntimeInterceptor::order))
                .toList();
    }

    @Override
    public AgentResult submit(AgentRequest request) {
        return submit(request, null);
    }

    @Override
    public AgentResult submit(AgentRequest request, AgentCallback callback) {
        return doSubmit(request, callback, null);
    }

    @Override
    public AgentResult submitStream(AgentRequest request, AgentCallback callback, ChatStreamCallback streamCallback) {
        return doSubmit(request, callback, streamCallback);
    }

    private AgentResult doSubmit(AgentRequest request, AgentCallback callback, ChatStreamCallback streamCallback) {
        List<AgentCallback> scopedCallbacks = new ArrayList<>(callbacks);
        if (callback != null) {
            scopedCallbacks.add(callback);
        }
        activeCallbacks.set(scopedCallbacks);
        AgentSession session = ensureSession(request);
        AgentRequest normalizedRequest = new AgentRequest(
                request.input(),
                session.id(),
                request.channelId(),
                request.userId(),
                request.metadata());
        AgentTask task = new AgentTask(UUID.randomUUID().toString(), normalizedRequest);
        attachSessionContext(task);
        task.markStatus(TaskStatus.RUNNING);
        taskStore.saveTask(task);
        String traceId = UUID.randomUUID().toString();
        putMdc(traceId, task);
        saveMessage(task.sessionId(), task.id(), "user", task.input());
        saveEvent(task, "INFO", "task.started", "任务开始", Map.of(
                "input", task.input(),
                "channelId", nullToEmpty(task.channelId()),
                "userId", nullToEmpty(task.userId())));
        emit("task.started", task.id(), request.input());
        log.info("agent task started input={}", preview(task.input()));
        log.debug("agent task input taskId={} input={}", task.id(), preview(task.input()));

        List<AgentStep> steps = new ArrayList<>();
        try {
            String directAnswer = runPlanner(task, steps);
            checkTaskCancelled(task);

            // 最终回复交给 ResponseGenerator 生成。
            // 当配置了真实模型时，这里会调用 LLM；Runtime 本身不再拼接模拟回答。
            String answer;
            if (directAnswer != null && !directAnswer.isBlank()) {
                // ReAct Planner 已确认 finished=true 时，可以直接使用 planner 产出的最终答案。
                answer = directAnswer;
                if (streamCallback != null) {
                    streamCallback.onDelta(answer);
                    streamCallback.onComplete(answer);
                }
            } else if (streamCallback != null && responseGenerator instanceof StreamingAgentResponseGenerator streamingGenerator) {
                LlmTraceContext.clear();
                answer = streamingGenerator.generateStream(task, steps, streamCallback);
                saveLlmTraces(task, "response-stream");
            } else {
                LlmTraceContext.clear();
                answer = responseGenerator.generate(task, steps);
                saveLlmTraces(task, "response");
            }
            checkTaskCancelled(task);
            task.complete(answer);
            taskStore.updateTask(task);
            saveMessage(task.sessionId(), task.id(), "assistant", answer);
            saveEvent(task, "INFO", "task.completed", "任务完成", Map.of(
                    "status", task.status().name(),
                    "answer", answer,
                    "stepCount", String.valueOf(steps.size())));
            emit("task.completed", task.id(), answer);
            log.info("agent task completed status={} stepCount={} output={}", task.status(), steps.size(), preview(answer));
            log.debug("agent final answer taskId={} answer={}", task.id(), preview(answer));
            return new AgentResult(task.id(), answer, task.status(), task.sessionId());
        } catch (TaskCancelledException e) {
            saveLlmTraces(task, "cancelled");
            task.markStatus(TaskStatus.CANCELLED);
            taskStore.updateTask(task);
            saveEvent(task, "WARN", "task.cancelled", "任务已取消", Map.of(
                    "status", task.status().name(),
                    "reason", nullToEmpty(e.getMessage())));
            emit("task.cancelled", task.id(), e.getMessage());
            log.warn("agent task cancelled taskId={} reason={}", task.id(), e.getMessage());
            return new AgentResult(task.id(), "任务已取消", task.status(), task.sessionId());
        } catch (RuntimeException e) {
            saveLlmTraces(task, "error");
            task.markStatus(TaskStatus.FAILED);
            taskStore.updateTask(task);
            saveEvent(task, "ERROR", "task.failed", "任务失败", Map.of(
                    "status", task.status().name(),
                    "error", nullToEmpty(e.getMessage())));
            emit("task.failed", task.id(), e.getMessage());
            log.error("agent task failed error={}", e.getMessage(), e);
            return new AgentResult(task.id(), "执行失败：" + e.getMessage(), task.status(), task.sessionId());
        } finally {
            cancelledTaskIds.remove(task.id());
            MDC.clear();
            activeCallbacks.remove();
            activeTodo.remove();
        }
    }

    private String runPlanner(AgentTask task, List<AgentStep> steps) {
        checkTaskCancelled(task);
        if (planner instanceof AgentReActPlanner reactPlanner) {
            return runReActPlanner(task, steps, reactPlanner);
        }
        LlmTraceContext.clear();
        List<ToolCall> plannedCalls = planner.plan(task);
        checkTaskCancelled(task);
        saveLlmTraces(task, "planner");
        log.info("agent planner finished toolCallCount={} calls={}", plannedCalls.size(), plannedCalls);
        log.debug("agent planner calls taskId={} calls={}", task.id(), plannedCalls);
        saveEvent(task, "INFO", "planner.finished", "工具规划完成", Map.of(
                "toolCallCount", String.valueOf(plannedCalls.size()),
                "calls", plannedCalls.toString()));
        executeToolCalls(task, steps, plannedCalls);
        checkTaskCancelled(task);
        return null;
    }

    private String runReActPlanner(AgentTask task, List<AgentStep> steps, AgentReActPlanner reactPlanner) {
        ToolFailureTracker failureTracker = new ToolFailureTracker();
        for (int round = 1; round <= maxReactRounds; round++) {
            checkTaskCancelled(task);
            LlmTraceContext.clear();
            AgentPlan plan = reactPlanner.planNext(task, steps, round);
            saveLlmTraces(task, "planner-react-" + round);
            checkTaskCancelled(task);
            log.info("agent react planner finished round={} finished={} toolCallCount={} calls={}",
                    round, plan.finished(), plan.calls().size(), plan.calls());
            saveEvent(task, "INFO", "planner.react.finished", "ReAct 规划完成", Map.of(
                    "round", String.valueOf(round),
                    "finished", String.valueOf(plan.finished()),
                    "toolCallCount", String.valueOf(plan.calls().size()),
                    "calls", plan.calls().toString()));
            if (plan.finished()) {
                String incompleteTodos = incompleteTodoSummary(task, steps);
                if (!incompleteTodos.isBlank()) {
                    // 当前任务涉及 Todo 时，最终答案不能和持久化 Todo 状态不一致，必须继续规划状态更新工具。
                    log.warn("agent react planner finish rejected by todo consistency guard taskId={} todos={}", task.id(), incompleteTodos);
                    saveEvent(task, "WARN", "planner.react.todo_incomplete", "ReAct 收口被 Todo 状态一致性拦截", Map.of(
                            "round", String.valueOf(round),
                            "todos", incompleteTodos));
                    addTodoConsistencyObservation(task, steps, incompleteTodos);
                    continue;
                }
                return plan.finalAnswer();
            }
            if (plan.calls().isEmpty()) {
                // 模型没有给出工具调用，也没有给最终答案时，退出 ReAct 循环，交给 ResponseGenerator 收口。
                return null;
            }
            executeToolCalls(task, steps, plan.calls(), failureTracker);
            checkTaskCancelled(task);
        }
        saveEvent(task, "WARN", "planner.react.max_rounds", "ReAct 达到最大轮次", Map.of(
                "maxRounds", String.valueOf(maxReactRounds)));
        log.warn("agent react planner reached max rounds taskId={} maxRounds={}", task.id(), maxReactRounds);
        String incompleteTodos = incompleteTodoSummary(task, steps);
        if (!incompleteTodos.isBlank()) {
            // 达到最大轮次后不能再交给最终回复模型发挥，否则会把未完成 Todo 说成已完成。
            throw new IllegalStateException("ReAct 达到最大轮次，仍有未完成 Todo：" + incompleteTodos);
        }
        return null;
    }

    private String incompleteTodoSummary(AgentTask task, List<AgentStep> steps) {
        if (todoStore == null || !shouldEnforceTodoConsistency(task, steps)) {
            return "";
        }
        List<TodoItem> items = todoStore.listTodoItems(task.sessionId(), "", 200);
        String latestPlanTaskId = items.stream()
                .max(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
        if (latestPlanTaskId.isBlank()) {
            return "";
        }
        List<TodoItem> latestPlanItems = items.stream()
                .filter(item -> latestPlanTaskId.equals(item.taskId()))
                .sorted(Comparator.comparingInt(TodoItem::itemOrder))
                .toList();
        List<TodoItem> incompleteItems = latestPlanItems.stream()
                .filter(item -> "pending".equalsIgnoreCase(item.status()) || "running".equalsIgnoreCase(item.status()))
                .toList();
        if (incompleteItems.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (TodoItem item : incompleteItems) {
            if (!summary.isEmpty()) {
                summary.append("; ");
            }
            summary.append(item.itemOrder()).append(". ").append(item.title()).append("=").append(item.status());
        }
        return summary.toString();
    }

    private boolean shouldEnforceTodoConsistency(AgentTask task, List<AgentStep> steps) {
        String input = task.input() == null ? "" : task.input().toLowerCase();
        boolean inputLooksLikeTodoWork = input.contains("todo") || input.contains("计划") || input.contains("执行全部") || input.contains("执行第");
        boolean currentTaskTouchedTodo = steps.stream().anyMatch(step -> step.name() != null && step.name().startsWith("builtin.todo."));
        return inputLooksLikeTodoWork || currentTaskTouchedTodo;
    }

    private void addTodoConsistencyObservation(AgentTask task, List<AgentStep> steps, String incompleteTodos) {
        AgentStep observation = new AgentStep(
                UUID.randomUUID().toString(),
                task.id(),
                StepType.OBSERVE,
                "system.todo_consistency_guard",
                Map.of("incompleteTodos", incompleteTodos));
        observation.succeed("持久化 Todo 仍未完成：" + incompleteTodos + "。不要直接输出最终完成报告，请继续调用 builtin.todo.update_item 和对应业务工具推进这些 Todo。");
        steps.add(observation);
    }

    private void executeToolCalls(AgentTask task, List<AgentStep> steps, List<ToolCall> calls) {
        executeToolCalls(task, steps, calls, null);
    }

    private void executeToolCalls(AgentTask task, List<AgentStep> steps, List<ToolCall> calls, ToolFailureTracker failureTracker) {
        checkTaskCancelled(task);
        List<IndexedToolCall> parallelBatch = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            checkTaskCancelled(task);
            ToolCall call = calls.get(i);
            if (isParallelReadOnlyTool(call)) {
                parallelBatch.add(new IndexedToolCall(i, call));
                continue;
            }
            flushParallelToolCalls(task, steps, parallelBatch, failureTracker);
            checkTaskCancelled(task);
            appendToolExecutionOutput(steps, executeToolCall(task, call, failureTracker));
        }
        flushParallelToolCalls(task, steps, parallelBatch, failureTracker);
        checkTaskCancelled(task);
    }

    private boolean isParallelReadOnlyTool(ToolCall call) {
        AgentTool tool = toolRegistry.find(call.toolId()).orElse(null);
        if (tool == null) {
            // 不存在的工具走顺序路径，让原有错误信息保持一致。
            return false;
        }
        ToolDefinition definition = tool.definition();
        if (definition == null || !"low".equalsIgnoreCase(definition.riskLevel())) {
            return false;
        }
        // 只把无状态、只读、低风险工具放入并发批次；Todo、安装、写文件、执行命令等仍保持顺序执行。
        return switch (call.toolId()) {
            case "builtin.web.fetch",
                    "builtin.web.search",
                    "builtin.content.read",
                    "builtin.github.repo_tree",
                    "builtin.github.read_file",
                    "builtin.filesystem.read_text_file",
                    "builtin.filesystem.list_directory",
                    "builtin.filesystem.search_files",
                    "builtin.filesystem.get_file_info",
                    "builtin.weather",
                    "builtin.time" -> true;
            default -> false;
        };
    }

    private void flushParallelToolCalls(AgentTask task, List<AgentStep> steps, List<IndexedToolCall> batch, ToolFailureTracker failureTracker) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            appendToolExecutionOutput(steps, executeToolCall(task, batch.remove(0).call(), failureTracker));
            return;
        }

        List<IndexedToolCall> currentBatch = new ArrayList<>(batch);
        batch.clear();
        List<AgentCallback> scopedCallbacks = activeCallbacks.get();
        TodoItem scopedTodo = activeTodo.get();
        Map<String, String> scopedMdc = MDC.getCopyOfContextMap();
        int poolSize = Math.min(MAX_PARALLEL_TOOL_CALLS, currentBatch.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<ToolExecutionOutput>> futures = new ArrayList<>();
            for (IndexedToolCall indexedCall : currentBatch) {
                futures.add(executor.submit(parallelToolTask(task, indexedCall, failureTracker, scopedCallbacks, scopedTodo, scopedMdc)));
            }
            List<ToolExecutionOutput> outputs = new ArrayList<>();
            for (Future<ToolExecutionOutput> future : futures) {
                try {
                    outputs.add(future.get());
                } catch (Exception e) {
                    throw new IllegalStateException("并发工具执行失败：" + e.getMessage(), e);
                }
            }
            checkTaskCancelled(task);
            outputs.stream()
                    .sorted(Comparator.comparingInt(ToolExecutionOutput::index))
                    .forEach(output -> appendToolExecutionOutput(steps, output));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<ToolExecutionOutput> parallelToolTask(AgentTask task, IndexedToolCall indexedCall, ToolFailureTracker failureTracker,
                                                           List<AgentCallback> scopedCallbacks, TodoItem scopedTodo, Map<String, String> scopedMdc) {
        return () -> {
            try {
                if (scopedCallbacks != null) {
                    activeCallbacks.set(scopedCallbacks);
                }
                if (scopedTodo != null) {
                    activeTodo.set(scopedTodo);
                }
                if (scopedMdc != null) {
                    MDC.setContextMap(scopedMdc);
                }
                return executeToolCall(task, indexedCall.call(), failureTracker).withIndex(indexedCall.index());
            } finally {
                activeCallbacks.remove();
                activeTodo.remove();
                MDC.clear();
            }
        };
    }

    private void appendToolExecutionOutput(List<AgentStep> steps, ToolExecutionOutput output) {
        taskStore.saveStep(output.step());
        steps.add(output.step());
        steps.addAll(output.observations());
    }

    private ToolExecutionOutput executeToolCall(AgentTask task, ToolCall call, ToolFailureTracker failureTracker) {
        checkTaskCancelled(task);
        AgentTool tool = toolRegistry.find(call.toolId())
                .orElseThrow(() -> new IllegalArgumentException("工具不存在：" + call.toolId()));
        AgentStep step = new AgentStep(UUID.randomUUID().toString(), task.id(), StepType.TOOL_CALL, call.toolId(), call.arguments());
        List<AgentStep> observations = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        Map<String, String> startedDetails = toolEventDetails(step, call, tool, null, null, 0L, startedAt);
        emit("step.started", task.id(), call.toolId(), streamToolDetails(task, startedDetails));
        emit("tool.started", task.id(), call.toolId(), streamToolDetails(task, startedDetails));
        log.info("agent tool started stepId={} toolId={} input={}", step.id(), call.toolId(), sanitizeText("arguments", call.arguments().toString()));
        log.debug("agent tool arguments taskId={} stepId={} arguments={}", task.id(), step.id(), sanitizeText("arguments", call.arguments().toString()));
        saveEvent(task, "DEBUG", "tool.started", "工具调用开始", startedDetails);

        if (failureTracker != null) {
            String blockReason;
            synchronized (failureTracker) {
                blockReason = failureTracker.blockReason(call).orElse("");
            }
            if (!blockReason.isBlank()) {
                // ReAct 场景下阻断重复失败调用，让下一轮模型基于失败原因换方案，而不是继续烧 token。
                step.fail(blockReason);
                saveEvent(task, "WARN", "tool.repeated_failure_blocked", "工具重复失败调用被阻断", Map.of(
                        "stepId", step.id(),
                        "toolId", call.toolId(),
                        "error", blockReason,
                        "toolKind", toolKind(call.toolId())));
                log.warn("agent tool repeated failure blocked stepId={} toolId={} reason={}", step.id(), call.toolId(), blockReason);
                Map<String, String> failedDetails = toolEventDetails(step, call, tool, null, blockReason, elapsedMs(startedAt), startedAt);
                emit("tool.failed", task.id(), preview(blockReason), streamToolDetails(task, failedDetails));
                emit("step.finished", task.id(), step.status().name(), streamToolDetails(task, failedDetails));
                return new ToolExecutionOutput(-1, step, observations);
            }
        }

        RuntimeException guardError = checkToolGuards(task, tool, call);
        if (guardError != null) {
            // 拦截失败只影响当前工具步骤，后续由 ResponseGenerator 汇总失败原因给用户。
            step.fail(guardError.getMessage());
            saveEvent(task, "WARN", "tool.blocked", "工具调用被安全策略拦截", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "error", nullToEmpty(guardError.getMessage()),
                    "toolKind", toolKind(call.toolId())));
            log.warn("agent tool blocked stepId={} toolId={} error={}", step.id(), call.toolId(), guardError.getMessage());
            Map<String, String> failedDetails = toolEventDetails(step, call, tool, null, guardError.getMessage(), elapsedMs(startedAt), startedAt);
            emit("tool.failed", task.id(), preview(guardError.getMessage()), streamToolDetails(task, failedDetails));
            emit("step.finished", task.id(), step.status().name(), streamToolDetails(task, failedDetails));
            return new ToolExecutionOutput(-1, step, observations);
        }

        checkTaskCancelled(task);
        ToolResult result = tool.execute(call, AgentContext.forTask(task));
        checkTaskCancelled(task);
        if (result.success()) {
            step.succeed(result.content());
            TodoItem todoAfterTool = updateActiveTodoAfterTool(task, call);
            if (failureTracker != null) {
                String recoveryMessage;
                synchronized (failureTracker) {
                    recoveryMessage = failureTracker.recordSuccess(call, result.content()).orElse("");
                }
                if (!recoveryMessage.isBlank()) {
                    observations.add(createFailureRecoveryObservation(task, call, recoveryMessage));
                }
            }
            log.info("agent tool succeeded stepId={} toolId={} output={}", step.id(), call.toolId(), sanitizeText("output", preview(result.content())));
            log.debug("agent tool output taskId={} stepId={} output={}", task.id(), step.id(), sanitizeText("output", preview(result.content())));
            Map<String, String> succeededDetails = toolEventDetails(step, call, tool, result.content(), null, elapsedMs(startedAt), startedAt);
            mergeTodoDetails(succeededDetails, todoAfterTool);
            succeededDetails.put("output", nullToEmpty(result.content()));
            saveEvent(task, "DEBUG", "tool.succeeded", "工具调用成功", succeededDetails);
            emit("tool.succeeded", task.id(), preview(result.content()), streamToolDetails(task, succeededDetails));
        } else {
            step.fail(result.content());
            if (failureTracker != null) {
                // 失败结果进入本任务的失败画像，下一轮相同参数会被策略判断是否允许重试。
                synchronized (failureTracker) {
                    failureTracker.recordFailure(call, result.content());
                }
            }
            log.warn("agent tool failed stepId={} toolId={} error={}", step.id(), call.toolId(), sanitizeText("error", preview(result.content())));
            Map<String, String> failedDetails = toolEventDetails(step, call, tool, null, result.content(), elapsedMs(startedAt), startedAt);
            saveEvent(task, "WARN", "tool.failed", "工具调用失败", failedDetails);
            emit("tool.failed", task.id(), preview(result.content()), streamToolDetails(task, failedDetails));
        }
        emit("step.finished", task.id(), step.status().name(), streamToolDetails(task, toolEventDetails(step, call, tool, result.content(), result.success() ? null : result.content(), elapsedMs(startedAt), startedAt)));
        return new ToolExecutionOutput(-1, step, observations);
    }

    private record IndexedToolCall(int index, ToolCall call) {
    }

    private record ToolExecutionOutput(int index, AgentStep step, List<AgentStep> observations) {
        private ToolExecutionOutput withIndex(int index) {
            return new ToolExecutionOutput(index, step, observations);
        }
    }

    private AgentStep createFailureRecoveryObservation(AgentTask task, ToolCall call, String message) {
        AgentStep observation = new AgentStep(
                UUID.randomUUID().toString(),
                task.id(),
                StepType.OBSERVE,
                "system.tool_failure_recovery",
                Map.of("toolId", call.toolId(), "arguments", call.arguments().toString()));
        observation.succeed(message);
        saveEvent(task, "WARN", "tool.failure_recovery_hint", "工具失败恢复提示", Map.of(
                "toolId", call.toolId(),
                "message", message));
        return observation;
    }

    private RuntimeException checkToolGuards(AgentTask task, AgentTool tool, ToolCall call) {
        for (ToolExecutionGuard guard : toolGuards) {
            try {
                // 多个 Guard 顺序执行，任意一个拒绝就阻断当前工具调用。
                guard.check(task, tool, call);
            } catch (RuntimeException e) {
                return e;
            }
        }
        return null;
    }

    @Override
    public AgentTask cancelTask(String taskId) {
        AgentTask task = getTask(taskId);
        if (isTerminalStatus(task.status())) {
            return task;
        }
        cancelledTaskIds.add(taskId);
        task.markStatus(TaskStatus.CANCELLED);
        taskStore.updateTask(task);
        saveEvent(task, "WARN", "task.cancel.requested", "任务取消请求已接收", Map.of(
                "status", task.status().name()));
        log.warn("agent task cancel requested taskId={}", taskId);
        return task;
    }

    private void checkTaskCancelled(AgentTask task) {
        if (cancelledTaskIds.contains(task.id()) || Thread.currentThread().isInterrupted()) {
            // 取消是协作式的：运行线程在规划、工具调用和最终回复边界检查，避免取消后继续写完成状态。
            throw new TaskCancelledException("用户请求停止执行");
        }
    }

    private boolean isTerminalStatus(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED;
    }

    private static class TaskCancelledException extends RuntimeException {
        private TaskCancelledException(String message) {
            super(message);
        }
    }

    @Override
    public AgentTask getTask(String taskId) {
        return taskStore.findTask(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
    }

    @Override
    public List<AgentStep> getSteps(String taskId) {
        return taskStore.findSteps(taskId);
    }

    @Override
    public List<AgentMessage> getTaskMessages(String taskId, int limit) {
        // 先校验任务存在，避免前端传错 taskId 时返回空数组掩盖问题。
        getTask(taskId);
        return messageStore.findMessagesByTask(taskId, limit);
    }

    @Override
    public AgentSession createSession(SessionCreateRequest request) {
        String title = request.title() == null || request.title().isBlank() ? "新会话" : request.title();
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), title, request.channelId(), request.userId(), request.metadata());
        sessionStore.saveSession(session);
        log.info("agent session created sessionId={} channelId={} userId={}", session.id(), session.channelId(), session.userId());
        return session;
    }

    @Override
    public String createSessionId() {
        String sessionId;
        do {
            // 只生成页面当前会话 ID，不创建 AgentSession，空会话不会落盘。
            sessionId = UUID.randomUUID().toString();
        } while (sessionStore.findSession(sessionId).isPresent());
        log.debug("agent session id allocated sessionId={}", sessionId);
        return sessionId;
    }

    @Override
    public Map<String, Object> clearAllSessions() {
        Set<Object> cleanedStores = Collections.newSetFromMap(new IdentityHashMap<>());
        int cleanedCount = 0;
        for (Object store : List.of(sessionStore, taskStore, messageStore, eventStore)) {
            if (store instanceof AgentDataCleaner cleaner && cleanedStores.add(store)) {
                // 同一个 SQLite Store 会实现多个接口，用 identity set 保证只清理一次。
                cleaner.clearAllAgentData();
                cleanedCount++;
            }
        }
        log.info("agent session data cleared storeCount={}", cleanedCount);
        return Map.of("success", true, "storeCount", cleanedCount);
    }

    @Override
    public AgentSession getSession(String sessionId) {
        return sessionStore.findSession(sessionId).orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    @Override
    public List<AgentSession> listSessions(int limit) {
        return sessionStore.listSessions(limit);
    }

    @Override
    public List<AgentTask> getSessionTasks(String sessionId, int limit) {
        getSession(sessionId);
        return taskStore.findTasksBySession(sessionId, limit);
    }

    @Override
    public List<AgentMessage> getSessionMessages(String sessionId, int limit) {
        getSession(sessionId);
        return messageStore.findMessages(sessionId, limit);
    }

    @Override
    public AgentSession summarizeSession(String sessionId, int limit) {
        AgentSession session = getSession(sessionId);
        List<AgentMessage> messages = messageStore.findMessages(sessionId, limit);
        log.info("agent session summarize started sessionId={} messageCount={}", sessionId, messages.size());
        // 摘要生成器可能是 LLM，也可能是本地规则；Runtime 只负责取消息和回写 session。
        String summary = sessionSummarizer.summarize(session, messages);
        session.updateSummary(summary);
        sessionStore.updateSession(session);
        promoteSessionMemory(session, messages);
        log.info("agent session summarize finished sessionId={} summaryLength={}", sessionId, summary.length());
        return session;
    }

    @Override
    public List<AgentEvent> getSessionEvents(String sessionId, int limit) {
        getSession(sessionId);
        return eventStore.findEventsBySession(sessionId, limit);
    }

    @Override
    public List<AgentEvent> getTaskEvents(String taskId, int limit) {
        getTask(taskId);
        return eventStore.findEventsByTask(taskId, limit);
    }

    private void emit(String type, String taskId, String message) {
        emit(type, taskId, message, Map.of());
    }

    private void emit(String type, String taskId, String message, Map<String, String> details) {
        List<AgentCallback> scopedCallbacks = activeCallbacks.get();
        if (scopedCallbacks == null) {
            scopedCallbacks = callbacks;
        }
        scopedCallbacks.forEach(callback -> callback.onEvent(type, taskId, message, details == null ? Map.of() : details));
    }

    private void promoteSessionMemory(AgentSession session, List<AgentMessage> messages) {
        for (MemoryPromoter promoter : memoryPromoters) {
            try {
                // 长期记忆提升失败不能影响会话摘要 API 的主流程。
                promoter.promoteSessionSummary(session, messages);
                log.info("agent memory promoted sessionId={} promoter={}", session.id(), promoter.getClass().getSimpleName());
            } catch (RuntimeException e) {
                log.warn("agent memory promote failed sessionId={} promoter={} error={}",
                        session.id(), promoter.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private AgentSession ensureSession(AgentRequest request) {
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return sessionStore.findSession(request.sessionId())
                    .map(session -> {
                        session.touch();
                        sessionStore.updateSession(session);
                        log.debug("agent session touched sessionId={}", session.id());
                        return session;
                    })
                    .orElseGet(() -> createSessionWithId(
                            request.sessionId(),
                            titleFromInput(request.input()),
                            request.channelId(),
                            request.userId(),
                            request.metadata()));
        }
        return createSession(new SessionCreateRequest(titleFromInput(request.input()), request.channelId(), request.userId(), request.metadata()));
    }

    private AgentSession createSessionWithId(String sessionId, String title, String channelId, String userId, Map<String, String> metadata) {
        // 前端已申请但未落盘的 sessionId，在第一条消息到达时正式成为持久化会话。
        AgentSession session = new AgentSession(sessionId, title, channelId, userId, metadata);
        sessionStore.saveSession(session);
        log.info("agent session created from requested id sessionId={} channelId={} userId={}", session.id(), session.channelId(), session.userId());
        return session;
    }

    private void saveMessage(String sessionId, String taskId, String role, String content) {
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), sessionId, taskId, role, content, java.util.Map.of());
        messageStore.saveMessage(message);
        log.debug("agent message saved sessionId={} taskId={} role={}", sessionId, taskId, role);
    }

    private void attachSessionContext(AgentTask task) {
        List<AgentMessage> messages = messageStore.findMessages(task.sessionId(), SESSION_CONTEXT_MESSAGE_LIMIT);
        if (messages.isEmpty()) {
            return;
        }
        StringBuilder context = new StringBuilder();
        for (AgentMessage message : messages) {
            String content = preview(message.content(), 1200);
            if (content.isBlank()) {
                continue;
            }
            // 只注入最近会话摘要式上下文，当前用户输入仍由 task.input 单独提供，避免重复。
            context.append(message.role()).append(": ").append(content).append("\n");
            if (context.length() >= SESSION_CONTEXT_CHAR_LIMIT) {
                break;
            }
        }
        String value = preview(context.toString(), SESSION_CONTEXT_CHAR_LIMIT);
        if (!value.isBlank()) {
            task.metadata().put(SESSION_CONTEXT_METADATA_KEY, value);
            log.debug("agent session context attached taskId={} chars={}", task.id(), value.length());
        }
    }

    private void saveLlmTraces(AgentTask task, String phase) {
        List<LlmCallTrace> traces = LlmTraceContext.drain();
        for (int i = 0; i < traces.size(); i++) {
            LlmCallTrace trace = traces.get(i);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("phase", phase);
            details.put("index", String.valueOf(i));
            details.put("model", nullToEmpty(trace.model()));
            details.put("baseUrl", nullToEmpty(trace.baseUrl()));
            details.put("statusCode", String.valueOf(trace.statusCode()));
            details.put("elapsedMs", String.valueOf(trace.elapsedMs()));
            details.put("promptTokens", String.valueOf(trace.promptTokens()));
            details.put("completionTokens", String.valueOf(trace.completionTokens()));
            details.put("totalTokens", String.valueOf(trace.totalTokens()));
            details.put("requestJson", nullToEmpty(trace.requestJson()));
            details.put("responseJson", nullToEmpty(trace.responseJson()));
            details.put("content", nullToEmpty(trace.content()));
            saveEvent(task, "DEBUG", "llm.call", "LLM 调用记录", details);
            log.debug("agent llm trace taskId={} phase={} model={} statusCode={} promptTokens={} completionTokens={} totalTokens={} requestJson={} responseJson={}",
                    task.id(), phase, trace.model(), trace.statusCode(), trace.promptTokens(), trace.completionTokens(), trace.totalTokens(),
                    sanitizeText("requestJson", trace.requestJson()), sanitizeText("responseJson", trace.responseJson()));
        }
    }

    private void saveEvent(AgentTask task, String level, String type, String message, Map<String, String> details) {
        AgentRuntimeInterceptorContext context = interceptorContext("event", type, message, task);
        Map<String, String> interceptedDetails = applyBeforeEvent(context, details);
        AgentEvent event = new AgentEvent(UUID.randomUUID().toString(), task.sessionId(), task.id(), level, type, message, interceptedDetails);
        eventStore.saveEvent(event);
        applyAfterEvent(context, interceptedDetails);
    }

    private Map<String, String> toolEventDetails(AgentStep step, ToolCall call, AgentTool tool, String output, String error, long elapsedMs, long startedAt) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("stepId", step.id());
        details.put("toolId", call.toolId());
        details.put("status", step.status().name());
        details.put("toolKind", toolKind(call.toolId()));
        details.put("inputPreview", sanitizeText("arguments", preview(call.arguments().toString())));
        details.put("arguments", sanitizeText("arguments", call.arguments().toString()));
        details.put("outputPreview", sanitizeText("output", preview(output)));
        details.put("outputLength", String.valueOf(output == null ? 0 : output.length()));
        details.put("error", sanitizeText("error", nullToEmpty(error)));
        details.put("elapsedMs", String.valueOf(elapsedMs));
        details.put("startedAt", String.valueOf(startedAt));
        mergeTodoDetails(details, activeTodo.get());
        return details;
    }

    private TodoItem updateActiveTodoAfterTool(AgentTask task, ToolCall call) {
        if (todoStore == null || !"builtin.todo.update_item".equals(call.toolId())) {
            return activeTodo.get();
        }
        String status = String.valueOf(call.arguments().getOrDefault("status", "")).toLowerCase();
        TodoItem item = findTodoForUpdateCall(task, call);
        if (item == null) {
            return activeTodo.get();
        }
        if ("running".equals(status)) {
            // Todo 进入 running 后，后续业务工具调用都挂到这个 Todo 上，便于页面点击查看执行日志。
            activeTodo.set(item);
            return item;
        }
        if ("completed".equals(status) || "failed".equals(status)) {
            TodoItem current = activeTodo.get();
            if (current != null && current.id().equals(item.id())) {
                activeTodo.remove();
            }
            return item;
        }
        return activeTodo.get();
    }

    private TodoItem findTodoForUpdateCall(AgentTask task, ToolCall call) {
        String id = String.valueOf(call.arguments().getOrDefault("id", ""));
        if (!id.isBlank()) {
            return todoStore.findTodoItem(id).orElse(null);
        }
        Object orderValue = call.arguments().get("order");
        if (orderValue == null) {
            return null;
        }
        int order;
        try {
            order = Integer.parseInt(String.valueOf(orderValue));
        } catch (NumberFormatException e) {
            return null;
        }
        List<TodoItem> items = todoStore.listTodoItems(task.sessionId(), "", 200);
        String latestPlanTaskId = items.stream()
                .max(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
        return items.stream()
                .filter(item -> latestPlanTaskId.equals(item.taskId()))
                .filter(item -> item.itemOrder() == order)
                .findFirst()
                .orElse(null);
    }

    private void mergeTodoDetails(Map<String, String> details, TodoItem todo) {
        if (todo == null) {
            return;
        }
        details.put("todoId", todo.id());
        details.put("todoTitle", nullToEmpty(todo.title()));
        details.put("todoOrder", String.valueOf(todo.itemOrder()));
    }

    private Map<String, String> streamToolDetails(AgentTask task, Map<String, String> details) {
        Map<String, String> streamDetails = new LinkedHashMap<>(applyBeforeStreamEvent(interceptorContext("stream", "tool", "工具流式事件", task), details));
        // SSE 只承载页面实时展示需要的摘要，完整输入输出留在 AgentEvent 里供弹窗按 stepId 查询。
        streamDetails.remove("arguments");
        streamDetails.remove("output");
        return streamDetails;
    }

    private Map<String, String> applyBeforeEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        Map<String, String> current = copyDetails(details);
        for (AgentRuntimeInterceptor interceptor : runtimeInterceptors) {
            // 前置拦截器按顺序串联，后一个拦截器只能看到前一个处理后的结果。
            current = copyDetails(interceptor.beforeEvent(context, current));
        }
        return current;
    }

    private Map<String, String> applyBeforeStreamEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        Map<String, String> current = copyDetails(details);
        for (AgentRuntimeInterceptor interceptor : runtimeInterceptors) {
            // SSE 事件同样走拦截器链，避免前端收到未脱敏或未规范化的字段。
            current = copyDetails(interceptor.beforeStreamEvent(context, current));
        }
        return current;
    }

    private void applyAfterEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        for (AgentRuntimeInterceptor interceptor : runtimeInterceptors) {
            // 后置拦截器只做旁路动作，不影响已经持久化的事件内容。
            interceptor.afterEvent(context, details);
        }
    }

    private Map<String, String> copyDetails(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(details);
    }

    private String sanitizeText(String key, String value) {
        String current = value;
        AgentRuntimeInterceptorContext context = interceptorContext("log", key, "Runtime 日志字段", null);
        for (AgentRuntimeInterceptor interceptor : runtimeInterceptors) {
            // 日志字段逐个通过拦截器处理，避免 requestJson/responseJson 中泄露敏感值。
            current = interceptor.beforeLogValue(context, key, current);
        }
        return current == null ? "" : current;
    }

    private AgentRuntimeInterceptorContext interceptorContext(String channel, String type, String message, AgentTask task) {
        return new AgentRuntimeInterceptorContext(channel, type, message, task);
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private void putMdc(String traceId, AgentTask task) {
        MDC.put("traceId", traceId);
        MDC.put("sessionId", nullToEmpty(task.sessionId()));
        MDC.put("taskId", nullToEmpty(task.id()));
        MDC.put("userId", nullToEmpty(task.userId()));
        MDC.put("channelId", nullToEmpty(task.channelId()));
    }

    private String toolKind(String toolId) {
        if (toolId == null) {
            return "";
        }
        if (toolId.startsWith("mcp.")) {
            return "mcp";
        }
        if (toolId.startsWith("builtin.")) {
            return "local";
        }
        return "custom";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String titleFromInput(String input) {
        String normalized = preview(input);
        if (normalized.isBlank()) {
            return "新会话";
        }
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private String preview(String text) {
        return preview(text, 200);
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
