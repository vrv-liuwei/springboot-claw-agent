package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.intent.IntentRisk;
import com.github.clawagent.intent.PendingAction;
import com.github.clawagent.intent.PendingActionCreateRequest;
import com.github.clawagent.intent.PendingActionExecutor;
import com.github.clawagent.intent.PendingActionResult;
import com.github.clawagent.intent.PendingActionService;
import com.github.clawagent.intent.PendingActionType;
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
import com.github.clawagent.core.TokenUsageSummary;
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
import com.github.clawagent.spi.MemoryCandidateProcessor;
import com.github.clawagent.spi.MemoryContextBuilder;
import com.github.clawagent.spi.MemoryExtractor;
import com.github.clawagent.spi.MemoryPromoter;
import com.github.clawagent.spi.MemoryProvider;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DefaultAgentRuntime 是 M1 的核心执行链路。
 * 这里刻意把“规划、工具查找、步骤落库、错误处理”显式拆开，便于后续替换为 LLM Planner 和审批流。
 */
public class DefaultAgentRuntime implements AgentRuntime {
    public static final String PROMPT_INJECTION_SUSPECTED_KEY = "security.promptInjectionSuspected";
    public static final String PROMPT_INJECTION_REASON_KEY = "security.promptInjectionReason";
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);
    /** 从模型 responseJson 中恢复 usage，避免 runtime 模块为了兜底解析额外依赖 Jackson。 */
    private static final Pattern PROMPT_TOKENS_PATTERN = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern COMPLETION_TOKENS_PATTERN = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern TOTAL_TOKENS_PATTERN = Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");
    /** 默认 ReAct 最大轮次，避免模型反复规划导致请求长时间不返回。 */
    private static final int DEFAULT_MAX_REACT_ROUNDS = 20;
    /** 传给模型的最近会话消息数量，避免短句追问丢失上下文。 */
    private static final int SESSION_CONTEXT_MESSAGE_LIMIT = 20;
    /** 会话上下文最大字符数，避免历史消息无限挤占工具列表和模型回复空间。 */
    private static final int SESSION_CONTEXT_CHAR_LIMIT = 10_000;
    /** Runtime 注入给 Planner/ResponseGenerator 的会话上下文 metadata key。 */
    private static final String SESSION_CONTEXT_METADATA_KEY = "runtime.sessionContext";
    /** Runtime 注入给 Planner/ResponseGenerator 的统一记忆上下文 metadata key。 */
    private static final String MEMORY_CONTEXT_METADATA_KEY = "runtime.memoryContext";
    /** Session metadata 中的上下文起点；/clear 和 /compact 会更新它来减少后续 prompt 历史。 */
    private static final String CONTEXT_ACTIVE_FROM_KEY = "context.activeFrom";
    /** Session metadata 中的上下文模式；compact 模式会把 session.summary 作为压缩摘要注入。 */
    private static final String CONTEXT_MODE_KEY = "context.mode";
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
    private final MemoryContextBuilder memoryContextBuilder;
    /** 候选记忆处理器。Runtime 只提交任务，实际提炼由异步/定时批处理器完成。 */
    private final MemoryCandidateProcessor memoryCandidateProcessor;
    private final AgentEventStore eventStore;
    private final TodoStore todoStore;
    private final List<ToolExecutionGuard> toolGuards;
    private final List<AgentCallback> callbacks;
    /** 已收到取消请求的任务 ID。运行线程会在规划、工具和最终回复边界协作式检查。 */
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();
    /** 正在执行的任务线程。取消时会同步打断线程，唤醒模型请求、长工具调用或并发等待。 */
    private final Map<String, Thread> activeTaskThreads = new ConcurrentHashMap<>();
    private final int maxReactRounds;
    /** Runtime 横切拦截器链，按 order 顺序处理脱敏、审计规范化、合规过滤等扩展逻辑。 */
    private final List<AgentRuntimeInterceptor> runtimeInterceptors;
    /** 当前提交请求的临时回调列表，用于单次任务流式推送运行事件。 */
    private final ThreadLocal<List<AgentCallback>> activeCallbacks = new ThreadLocal<>();
    /** 当前正在执行的 Todo，用于把后续工具调用日志挂到具体 Todo 上。 */
    private final ThreadLocal<TodoItem> activeTodo = new ThreadLocal<>();
    /** 等待前端审批的工具调用；当前先用内存态保证本地 Web Agent 的审批阻塞流程跑通。 */
    private final Map<String, PendingToolApproval> pendingApprovals = new ConcurrentHashMap<>();
    /** 统一待确认动作服务，用于把工具审批同步暴露给 IM 通道的“确认执行”文本流程。 */
    private final PendingActionService pendingActionService;

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, null, List.of(), null, eventStore, null, toolGuards, callbacks,
                DEFAULT_MAX_REACT_ROUNDS, List.of(new SensitiveDataInterceptor(SanitizationOptions.defaults())));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, null, List.of(), null, eventStore, todoStore, toolGuards, callbacks,
                DEFAULT_MAX_REACT_ROUNDS, List.of(new SensitiveDataInterceptor(SanitizationOptions.defaults())));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, null, List.of(), null, eventStore, todoStore, toolGuards, callbacks,
                maxReactRounds, List.of(new SensitiveDataInterceptor(SanitizationOptions.defaults())));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, SanitizationOptions sanitizationOptions) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, null, List.of(), null, eventStore, todoStore, toolGuards, callbacks,
                maxReactRounds, List.of(new SensitiveDataInterceptor(sanitizationOptions)));
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, null, List.of(), null, eventStore, todoStore, toolGuards, callbacks,
                maxReactRounds, runtimeInterceptors);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, MemoryContextBuilder memoryContextBuilder, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, memoryContextBuilder, List.of(), null, eventStore, todoStore, toolGuards, callbacks,
                maxReactRounds, runtimeInterceptors);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, MemoryContextBuilder memoryContextBuilder, List<MemoryExtractor> memoryExtractors, MemoryProvider memoryProvider, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, memoryContextBuilder,
                defaultMemoryCandidateProcessor(sessionStore, messageStore, eventStore, memoryExtractors, memoryProvider),
                eventStore, todoStore, toolGuards, callbacks,
                maxReactRounds, runtimeInterceptors);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, MemoryContextBuilder memoryContextBuilder, MemoryCandidateProcessor memoryCandidateProcessor, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors) {
        this(planner, responseGenerator, toolRegistry, taskStore, sessionStore, messageStore, sessionSummarizer,
                memoryPromoters, memoryContextBuilder, memoryCandidateProcessor, eventStore, todoStore, toolGuards,
                callbacks, maxReactRounds, runtimeInterceptors, null);
    }

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, MemoryContextBuilder memoryContextBuilder, MemoryCandidateProcessor memoryCandidateProcessor, AgentEventStore eventStore, TodoStore todoStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks, int maxReactRounds, List<AgentRuntimeInterceptor> runtimeInterceptors, PendingActionService pendingActionService) {
        this.planner = planner;
        this.responseGenerator = responseGenerator;
        this.toolRegistry = toolRegistry;
        this.taskStore = taskStore;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.sessionSummarizer = sessionSummarizer;
        this.memoryPromoters = memoryPromoters == null ? List.of() : List.copyOf(memoryPromoters);
        this.memoryContextBuilder = memoryContextBuilder;
        this.memoryCandidateProcessor = memoryCandidateProcessor;
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
        this.pendingActionService = pendingActionService;
    }

    private static MemoryCandidateProcessor defaultMemoryCandidateProcessor(SessionStore sessionStore,
                                                                            SessionMessageStore messageStore,
                                                                            AgentEventStore eventStore,
                                                                            List<MemoryExtractor> memoryExtractors,
                                                                            MemoryProvider memoryProvider) {
        if (memoryProvider == null || memoryExtractors == null || memoryExtractors.isEmpty()) {
            return null;
        }
        // 兼容直接使用旧构造器的场景：仍然提炼候选记忆，但默认走异步队列，不阻塞任务完成。
        return new DefaultMemoryCandidateProcessor(
                sessionStore,
                messageStore,
                eventStore,
                memoryExtractors,
                memoryProvider,
                MemoryCandidateProcessingOptions.defaults());
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
        AgentRequest interceptedRequest = applyBeforeRequest(request);
        AgentSession session = ensureSession(interceptedRequest);
        AgentRequest normalizedRequest = withSessionWorkspace(interceptedRequest, session);
        AgentTask task = new AgentTask(UUID.randomUUID().toString(), normalizedRequest);
        attachSessionContext(task);
        task.markStatus(TaskStatus.RUNNING);
        taskStore.saveTask(task);
        activeTaskThreads.put(task.id(), Thread.currentThread());
        String traceId = UUID.randomUUID().toString();
        putMdc(traceId, task);
        saveMessage(task.sessionId(), task.id(), "user", task.input(), messageMetadata(task.metadata()));
        saveEvent(task, "INFO", "task.started", "任务开始", Map.of(
                "input", task.input(),
                "channelId", nullToEmpty(task.channelId()),
                "userId", nullToEmpty(task.userId())));
        saveApprovalEvent(task);
        emit("task.started", task.id(), request.input());
        saveResumeRequestEvent(task);
        emitResumeCheckpoint(task);
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
            enqueueMemoryCandidates(task, answer);
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
        } catch (ContinuationRequiredException e) {
            saveLlmTraces(task, "continuation-required");
            String answer = continuationAnswer(e.incompleteTodos());
            task.requireContinuation(answer);
            taskStore.updateTask(task);
            saveMessage(task.sessionId(), task.id(), "assistant", answer);
            saveEvent(task, "WARN", "task.continuation_required", "任务需要继续执行", Map.of(
                    "status", task.status().name(),
                    "maxRounds", String.valueOf(maxReactRounds),
                    "todos", e.incompleteTodos()));
            emit("task.continuation_required", task.id(), answer, Map.of(
                    "status", task.status().name(),
                    "maxRounds", String.valueOf(maxReactRounds),
                    "todos", e.incompleteTodos()));
            log.warn("agent task continuation required taskId={} maxRounds={} todos={}", task.id(), maxReactRounds, e.incompleteTodos());
            return new AgentResult(task.id(), answer, task.status(), task.sessionId());
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
            activeTaskThreads.remove(task.id(), Thread.currentThread());
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
            throw new ContinuationRequiredException(incompleteTodos);
        }
        return null;
    }

    private String continuationAnswer(String incompleteTodos) {
        return "本轮已达到单次执行上限（maxReactRounds=" + maxReactRounds + "），仍有未完成 Todo："
                + incompleteTodos
                + "。\n\n这不是执行失败。请继续本会话，Agent 会基于当前 Todo 和上下文接着处理。";
    }

    private String incompleteTodoSummary(AgentTask task, List<AgentStep> steps) {
        if (todoStore == null || !shouldEnforceTodoConsistency(task, steps)) {
            return "";
        }
        List<TodoItem> items = todoStore.listTodoItems(task.sessionId(), "", 200);
        List<TodoItem> latestPlanItems = selectRelevantTodos(task, items);
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
        if (isSimpleLocalAction(input)) {
            // 启动/停止/查看状态这类单步操作不应被 Todo 一致性守卫拖成长任务；即使模型误建 Todo，也允许直接收口。
            return false;
        }
        boolean inputLooksLikeTodoWork = input.contains("todo") || input.contains("计划") || input.contains("执行全部") || input.contains("执行第");
        boolean currentTaskTouchedTodo = steps.stream().anyMatch(step -> step.name() != null && step.name().startsWith("builtin.todo."));
        return inputLooksLikeTodoWork || currentTaskTouchedTodo;
    }

    private boolean isSimpleLocalAction(String input) {
        return input.contains("启动项目")
                || input.contains("运行项目")
                || input.contains("停止项目")
                || input.contains("重启项目")
                || input.contains("查看状态")
                || input.contains("查看日志")
                || input.contains("git status")
                || input.contains("执行测试")
                || input.contains("运行测试")
                || input.contains("安装依赖");
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
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    futures.forEach(item -> item.cancel(true));
                    checkTaskCancelled(task);
                    throw new TaskCancelledException("并发工具等待被中断");
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
        Map<String, String> stepInput = new LinkedHashMap<>(call.arguments());
        // 动态风险等级依赖本次参数，落到 Step input 后跨任务检索可按 riskLevel 精确过滤。
        stepInput.put("riskLevel", nullToEmpty(tool.riskLevel(call)).toLowerCase());
        AgentStep step = new AgentStep(UUID.randomUUID().toString(), task.id(), StepType.TOOL_CALL, call.toolId(), stepInput);
        List<AgentStep> observations = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        Map<String, String> startedDetails = toolEventDetails(task, step, call, tool, null, null, 0L, startedAt);
        emit("step.started", task.id(), call.toolId(), streamToolDetails(task, startedDetails));
        emit("tool.started", task.id(), call.toolId(), streamToolDetails(task, startedDetails));
        log.info("agent tool started stepId={} toolId={} input={}", step.id(), call.toolId(), sanitizeText("arguments", call.arguments().toString()));
        log.debug("agent tool arguments taskId={} stepId={} arguments={}", task.id(), step.id(), sanitizeText("arguments", call.arguments().toString()));
        saveEvent(task, "DEBUG", "tool.started", "工具调用开始", startedDetails);
        detectPromptInjectionBeforeTool(task, call, step);

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
                Map<String, String> failedDetails = toolEventDetails(task, step, call, tool, null, blockReason, elapsedMs(startedAt), startedAt);
                emit("tool.failed", task.id(), preview(blockReason), streamToolDetails(task, failedDetails));
                emit("step.finished", task.id(), step.status().name(), streamToolDetails(task, failedDetails));
                return new ToolExecutionOutput(-1, step, observations);
            }
        }

        RuntimeException guardError = checkToolGuards(task, tool, call);
        if (guardError != null) {
            waitForToolApproval(task, step, call, tool, guardError, startedAt);
        }

        checkTaskCancelled(task);
        ToolResult result = tool.execute(call, AgentContext.forTask(task));
        checkTaskCancelled(task);
        if (result.success()) {
            step.succeed(result.content());
            detectPromptInjectionFromToolOutput(task, call, step, result.content());
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
            Map<String, String> succeededDetails = toolEventDetails(task, step, call, tool, result.content(), null, elapsedMs(startedAt), startedAt);
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
            Map<String, String> failedDetails = toolEventDetails(task, step, call, tool, null, result.content(), elapsedMs(startedAt), startedAt);
            saveEvent(task, "WARN", "tool.failed", "工具调用失败", failedDetails);
            emit("tool.failed", task.id(), preview(result.content()), streamToolDetails(task, failedDetails));
        }
        emit("step.finished", task.id(), step.status().name(), streamToolDetails(task, toolEventDetails(task, step, call, tool, result.content(), result.success() ? null : result.content(), elapsedMs(startedAt), startedAt)));
        return new ToolExecutionOutput(-1, step, observations);
    }

    private record IndexedToolCall(int index, ToolCall call) {
    }

    private record ToolExecutionOutput(int index, AgentStep step, List<AgentStep> observations) {
        private ToolExecutionOutput withIndex(int index) {
            return new ToolExecutionOutput(index, step, observations);
        }
    }

    private void waitForToolApproval(AgentTask task, AgentStep step, ToolCall call, AgentTool tool,
                                     RuntimeException guardError, long startedAt) {
        String approvalKey = approvalKey(task.id(), step.id());
        PendingToolApproval pending = new PendingToolApproval(task.id(), step.id(), call.toolId(), new CompletableFuture<>());
        pendingApprovals.put(approvalKey, pending);
        task.markStatus(TaskStatus.WAITING_APPROVAL);
        taskStore.updateTask(task);
        Map<String, String> details = toolEventDetails(task, step, call, tool, null, guardError.getMessage(), elapsedMs(startedAt), startedAt);
        details.put("approvalKey", approvalKey);
        // 仍保留原 pendingApprovals 阻塞机制，同时注册 PendingAction，保证 Web 审批和 IM 文本确认走同一套状态。
        PendingAction pendingAction = createToolPendingAction(task, step, call, guardError.getMessage(), pending);
        if (pendingAction != null) {
            details.put("pendingActionId", pendingAction.actionId());
            details.put("confirmText", pendingAction.confirmText());
        }
        saveEvent(task, "WARN", "tool.approval_requested", "工具调用等待用户审批", details);
        emit("tool.approval_requested", task.id(), "工具调用等待用户审批", streamToolDetails(task, details));
        log.warn("agent tool waiting approval taskId={} stepId={} toolId={} reason={}",
                task.id(), step.id(), call.toolId(), guardError.getMessage());
        try {
            // 高危工具在这里阻塞当前任务线程；审批通过后沿用同一个 task/step/context 继续执行。
            boolean approved = pending.future().get();
            if (!approved) {
                String rejectReason = approvalRejectReason(task);
                saveEvent(task, "WARN", "tool.approval_rejected", "工具调用审批已拒绝", Map.of(
                        "stepId", step.id(),
                        "toolId", call.toolId(),
                        "approvalKey", approvalKey,
                        "reason", rejectReason));
                emit("tool.approval_rejected", task.id(), "工具调用审批已拒绝", Map.of(
                        "stepId", step.id(),
                        "toolId", call.toolId(),
                        "approvalKey", approvalKey,
                        "reason", rejectReason));
                throw new TaskCancelledException("工具审批被拒绝：" + call.toolId());
            }
            task.metadata().merge("approvedToolIds", call.toolId(), (left, right) ->
                    containsMetadataToken(left, right) ? left : left + "," + right);
            task.markStatus(TaskStatus.RUNNING);
            taskStore.updateTask(task);
            saveEvent(task, "INFO", "tool.approval_granted", "工具调用已审批，继续执行", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "approvalKey", approvalKey));
            emit("tool.approval_granted", task.id(), "工具调用已审批，继续执行", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "approvalKey", approvalKey));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskCancelledException("等待工具审批被中断：" + call.toolId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new TaskCancelledException("等待工具审批失败：" + nullToEmpty(cause == null ? null : cause.getMessage()));
        } finally {
            pendingApprovals.remove(approvalKey);
        }
    }

    private String approvalKey(String taskId, String stepId) {
        return nullToEmpty(taskId) + ":" + nullToEmpty(stepId);
    }

    private PendingAction createToolPendingAction(AgentTask task, AgentStep step, ToolCall call, String reason, PendingToolApproval pending) {
        if (pendingActionService == null) {
            return null;
        }
        // 工具审批统一按高风险处理，需要用户回复完整确认文本，避免一句泛化确认误放行命令类工具。
        return pendingActionService.create(new PendingActionCreateRequest(
                PendingActionType.TOOL_APPROVAL,
                "工具调用 " + call.toolId(),
                reason,
                IntentRisk.HIGH,
                task.sessionId(),
                task.channelId(),
                task.userId(),
                task.id(),
                step.id(),
                call.toolId(),
                Map.of("approvalKey", approvalKey(task.id(), step.id()), "toolId", call.toolId()),
                Duration.ofMinutes(10)),
                new PendingActionExecutor() {
                    @Override
                    public String confirm(PendingAction action, String input) {
                        pending.future().complete(true);
                        return "已确认工具调用：" + call.toolId();
                    }

                    @Override
                    public String reject(PendingAction action, String rejectReason) {
                        AgentTask current = getTask(task.id());
                        current.metadata().put("lastApprovalRejectReason", nullToEmpty(rejectReason).isBlank() ? "用户拒绝审批" : rejectReason);
                        taskStore.updateTask(current);
                        pending.future().complete(false);
                        return "已拒绝工具调用：" + call.toolId();
                    }
                });
    }
    private boolean containsMetadataToken(String value, String expected) {
        if (value == null || value.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        String normalizedExpected = expected.trim();
        for (String token : value.split("[,;\\s]+")) {
            if (normalizedExpected.equals(token.trim())) {
                return true;
            }
        }
        return false;
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

    private void detectPromptInjectionBeforeTool(AgentTask task, ToolCall call, AgentStep step) {
        String evidence = promptInjectionEvidence(task.input() + "\n" + toolControlText(call));
        if (!evidence.isBlank()) {
            markPromptInjectionSuspected(task, step, call, "tool-input", evidence);
        }
    }

    private void detectPromptInjectionFromToolOutput(AgentTask task, ToolCall call, AgentStep step, String output) {
        String evidence = promptInjectionEvidence(output);
        if (!evidence.isBlank()) {
            markPromptInjectionSuspected(task, step, call, "tool-output", evidence);
        }
    }

    private void markPromptInjectionSuspected(AgentTask task, AgentStep step, ToolCall call, String source, String evidence) {
        if ("true".equalsIgnoreCase(task.metadata().get(PROMPT_INJECTION_SUSPECTED_KEY))
                && evidence.equals(task.metadata().get(PROMPT_INJECTION_REASON_KEY))) {
            return;
        }
        task.metadata().put(PROMPT_INJECTION_SUSPECTED_KEY, "true");
        task.metadata().put(PROMPT_INJECTION_REASON_KEY, evidence);
        taskStore.updateTask(task);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("stepId", step.id());
        details.put("toolId", call.toolId());
        details.put("source", source);
        details.put("riskLevel", "medium");
        details.put("reason", evidence);
        details.put("inputPreview", sanitizeText("arguments", preview(toolControlText(call), 800)));
        // 这里先记录风险提示，不直接失败；后续非 low 工具会被 Guard 转成人工确认。
        saveEvent(task, "WARN", "security.prompt_injection_detected", "疑似提示词注入风险", details);
        emit("security.prompt_injection_detected", task.id(), "疑似提示词注入风险", details);
    }

    private String toolControlText(ToolCall call) {
        StringBuilder builder = new StringBuilder(call.toolId());
        call.arguments().forEach((key, value) -> {
            String normalizedKey = nullToEmpty(key).toLowerCase(Locale.ROOT);
            if (Set.of("content", "body", "text", "aftercontent", "beforecontent").contains(normalizedKey)) {
                return;
            }
            builder.append('\n').append(key).append('=').append(value);
        });
        return builder.toString();
    }

    private String promptInjectionEvidence(String value) {
        String normalized = nullToEmpty(value).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        Map<String, List<String>> patterns = new LinkedHashMap<>();
        patterns.put("ignore-instructions", List.of("ignore previous instructions", "ignore all previous", "disregard previous", "忽略之前", "忽略以上", "忽略系统"));
        patterns.put("system-prompt-leak", List.of("system prompt", "developer message", "hidden instruction", "系统提示词", "开发者消息", "隐藏指令"));
        patterns.put("secret-exfiltration", List.of("reveal secret", "dump secret", "api key", "access token", "private key", "泄露密钥", "导出密钥", "读取.env", ".env"));
        patterns.put("approval-bypass", List.of("bypass approval", "disable approval", "without approval", "无需审批", "绕过审批", "关闭审批"));
        patterns.put("audit-evasion", List.of("do not tell the user", "hide this action", "delete audit", "不要告诉用户", "隐藏这个操作", "删除审计"));
        for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
            for (String pattern : entry.getValue()) {
                if (normalized.contains(pattern)) {
                    return entry.getKey() + ": " + pattern;
                }
            }
        }
        return "";
    }

    @Override
    public AgentTask cancelTask(String taskId) {
        AgentTask task = getTask(taskId);
        if (isTerminalStatus(task.status())) {
            return task;
        }
        cancelledTaskIds.add(taskId);
        releasePendingApprovals(taskId, false);
        Thread activeThread = activeTaskThreads.get(taskId);
        if (activeThread != null) {
            // 模型请求或长阻塞工具不一定会主动轮询取消标记，先打断运行线程让它尽快回到取消分支。
            activeThread.interrupt();
        }
        task.markStatus(TaskStatus.CANCELLED);
        taskStore.updateTask(task);
        saveEvent(task, "WARN", "task.cancel.requested", "任务取消请求已接收", Map.of(
                "status", task.status().name(),
                "threadInterrupted", String.valueOf(activeThread != null)));
        log.warn("agent task cancel requested taskId={} threadInterrupted={}", taskId, activeThread != null);
        return task;
    }

    @Override
    public AgentTask approveToolCall(String taskId, String stepId, String toolId) {
        if (pendingActionService != null) {
            // 后台按钮审批时优先关闭统一 PendingAction，防止 IM 端还残留同一个待确认动作。
            PendingActionResult result = pendingActionService.confirmByTarget(PendingActionType.TOOL_APPROVAL, taskId, stepId, toolId, "确认执行：" + toolId);
            if (result.handled()) {
                return getTask(taskId);
            }
        }
        String approvalKey = approvalKey(taskId, stepId);
        PendingToolApproval pending = pendingApprovals.get(approvalKey);
        if (pending == null) {
            throw new IllegalArgumentException("没有待审批的工具调用：" + approvalKey);
        }
        if (!pending.toolId().equals(toolId)) {
            throw new IllegalArgumentException("审批工具不匹配，期望 " + pending.toolId() + "，实际 " + toolId);
        }
        pending.future().complete(true);
        return getTask(taskId);
    }

    @Override
    public AgentTask rejectToolCall(String taskId, String stepId, String toolId, String reason) {
        if (pendingActionService != null) {
            // 拒绝路径同样先同步 PendingAction 状态，再回退到旧的 pendingApprovals 阻塞对象。
            PendingActionResult result = pendingActionService.rejectByTarget(PendingActionType.TOOL_APPROVAL, taskId, stepId, toolId, reason);
            if (result.handled()) {
                return getTask(taskId);
            }
        }
        String approvalKey = approvalKey(taskId, stepId);
        PendingToolApproval pending = pendingApprovals.get(approvalKey);
        if (pending == null) {
            throw new IllegalArgumentException("没有待审批的工具调用：" + approvalKey);
        }
        if (!pending.toolId().equals(toolId)) {
            throw new IllegalArgumentException("审批工具不匹配，期望 " + pending.toolId() + "，实际 " + toolId);
        }
        AgentTask task = getTask(taskId);
        // 拒绝原因先写入 task metadata，等待中的任务线程被唤醒后统一记录审批拒绝事件。
        task.metadata().put("lastApprovalRejectReason", nullToEmpty(reason).isBlank() ? "用户拒绝审批" : reason);
        taskStore.updateTask(task);
        pending.future().complete(false);
        return task;
    }

    private String approvalRejectReason(AgentTask task) {
        String reason = nullToEmpty(task.metadata().get("lastApprovalRejectReason"));
        return reason.isBlank() ? "用户拒绝审批" : reason;
    }

    private void releasePendingApprovals(String taskId, boolean approved) {
        for (PendingToolApproval pending : pendingApprovals.values()) {
            if (pending.taskId().equals(taskId)) {
                pending.future().complete(approved);
            }
        }
    }

    private void checkTaskCancelled(AgentTask task) {
        if (cancelledTaskIds.contains(task.id()) || Thread.currentThread().isInterrupted()) {
            // 取消是协作式的：运行线程在规划、工具调用和最终回复边界检查，避免取消后继续写完成状态。
            throw new TaskCancelledException("用户请求停止执行");
        }
    }

    private boolean isTerminalStatus(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED
                || status == TaskStatus.CONTINUATION_REQUIRED;
    }

    private static class TaskCancelledException extends RuntimeException {
        private TaskCancelledException(String message) {
            super(message);
        }
    }

    private static class ContinuationRequiredException extends RuntimeException {
        private final String incompleteTodos;

        private ContinuationRequiredException(String incompleteTodos) {
            super("ReAct 达到最大轮次，仍有未完成 Todo：" + incompleteTodos);
            this.incompleteTodos = incompleteTodos;
        }

        private String incompleteTodos() {
            return incompleteTodos;
        }
    }

    private record PendingToolApproval(String taskId, String stepId, String toolId, CompletableFuture<Boolean> future) {
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
        return hydrateMessageMetadata(messageStore.findMessagesByTask(taskId, limit));
    }

    @Override
    public AgentSession createSession(SessionCreateRequest request) {
        String title = request.title() == null || request.title().isBlank() ? "新会话" : request.title();
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), title, request.channelId(), request.userId(),
                normalizeWorkspaceMetadata(request.workspaceId(), request.metadata()));
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
        return hydrateMessageMetadata(messageStore.findMessages(sessionId, limit));
    }

    @Override
    public List<AgentMessage> getSessionMessagesBefore(String sessionId, java.time.Instant beforeCreatedAt, int limit) {
        getSession(sessionId);
        // 滚动分页加载旧消息也要补齐附件 metadata，保证刷新前后聊天卡片展示一致。
        return hydrateMessageMetadata(messageStore.findMessagesBefore(sessionId, beforeCreatedAt, limit));
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

    @Override
    public List<AgentEvent> queryEvents(Instant from, Instant to, String level, String type, String sessionId, String taskId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        // 全局审计查询只读 AgentEventStore，不触发 task/session 存在性校验，避免历史事件因任务清理而不可检索。
        return eventStore.findEvents(from, to, level, type, sessionId, taskId, safeLimit);
    }

    @Override
    public void recordTaskEvent(String taskId, String level, String type, String message, Map<String, String> details) {
        AgentTask task = getTask(taskId);
        // 管理台触发的手动动作也进入同一条事件链，文件审查和审计查询才能按任务恢复上下文。
        saveEvent(task, level, type, message, details == null ? Map.of() : details);
        emit(type, task.id(), message, details == null ? Map.of() : details);
    }

    @Override
    public TokenUsageSummary getSessionTokenUsage(String sessionId, int limit) {
        getSession(sessionId);
        // Token usage 当前从 llm.call 事件汇总；limit 用来控制历史窗口，避免一次性扫太多审计记录。
        return aggregateTokenUsage("session", sessionId, eventStore.findEventsBySession(sessionId, limit));
    }

    @Override
    public TokenUsageSummary getTaskTokenUsage(String taskId) {
        getTask(taskId);
        // 单任务事件量有限，给足够大的窗口，避免多轮 ReAct 的 LLM 调用被截断。
        return aggregateTokenUsage("task", taskId, eventStore.findEventsByTask(taskId, 1000));
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

    private void enqueueMemoryCandidates(AgentTask task, String answer) {
        if (memoryCandidateProcessor == null) {
            return;
        }
        // 这里必须只做快速入队，不能在 Runtime 主线程直接调用记忆模型。
        memoryCandidateProcessor.onTaskCompleted(task, answer);
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
        AgentSession session = new AgentSession(sessionId, title, channelId, userId, normalizeWorkspaceMetadata("", metadata));
        sessionStore.saveSession(session);
        log.info("agent session created from requested id sessionId={} channelId={} userId={}", session.id(), session.channelId(), session.userId());
        return session;
    }

    private AgentRequest withSessionWorkspace(AgentRequest request, AgentSession session) {
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        inheritWorkspace(metadata, session);
        return new AgentRequest(request.input(), session.id(), request.channelId(), request.userId(), metadata);
    }

    private Map<String, String> normalizeWorkspaceMetadata(String workspaceId, Map<String, String> source) {
        Map<String, String> metadata = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if (workspaceId != null && !workspaceId.isBlank()) {
            metadata.putIfAbsent("workspaceId", workspaceId);
        }
        aliasWorkspace(metadata);
        return metadata;
    }

    private void inheritWorkspace(Map<String, String> metadata, AgentSession session) {
        putIfPresent(metadata, "workspaceId", firstNonBlank(session.workspaceId(), metadata.get("workspaceId"), ""));
        putIfPresent(metadata, "workspaceName", firstNonBlank(session.workspaceName(), metadata.get("workspaceName"), ""));
        putIfPresent(metadata, "workspaceRoot", firstNonBlank(session.workspaceRoot(), metadata.get("workspaceRoot"), ""));
        aliasWorkspace(metadata);
    }

    private void aliasWorkspace(Map<String, String> metadata) {
        putIfPresent(metadata, "workspace.id", metadata.get("workspaceId"));
        putIfPresent(metadata, "workspace.name", metadata.get("workspaceName"));
        putIfPresent(metadata, "workspace.root", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "workspace.projectPath", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "projectPath", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "activeProjectPath", metadata.get("workspaceRoot"));
    }

    private void saveMessage(String sessionId, String taskId, String role, String content) {
        saveMessage(sessionId, taskId, role, content, java.util.Map.of());
    }

    private void saveMessage(String sessionId, String taskId, String role, String content, Map<String, String> metadata) {
        // 用户消息需要保留附件、知识库范围等轻量 metadata，刷新历史会话时才能恢复附件卡片。
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), sessionId, taskId, role, content, metadata);
        messageStore.saveMessage(message);
        log.debug("agent message saved sessionId={} taskId={} role={}", sessionId, taskId, role);
    }

    private Map<String, String> messageMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Set<String> allowedKeys = Set.of(
                "attachments",
                "attachmentIds",
                "attachmentStoragePaths",
                "attachmentKnowledgeDocumentIds",
                "knowledge.enabled",
                "knowledge.documentIds",
                "knowledge.scope",
                "knowledge.intent");
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private List<AgentMessage> hydrateMessageMetadata(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> {
                    if (!"user".equals(message.role()) || message.metadata().containsKey("attachments") || message.taskId() == null) {
                        return message;
                    }
                    return taskStore.findTask(message.taskId())
                            .map(task -> {
                                Map<String, String> metadata = messageMetadata(task.metadata());
                                if (metadata.isEmpty()) {
                                    return message;
                                }
                                // 兼容旧消息：早期 user message 未保存附件 metadata，读取历史时从 task metadata 轻量补齐。
                                return new AgentMessage(message.id(), message.sessionId(), message.taskId(),
                                        message.role(), message.content(), metadata, message.createdAt());
                            })
                            .orElse(message);
                })
                .toList();
    }

    private void attachSessionContext(AgentTask task) {
        AgentSession session = sessionStore.findSession(task.sessionId()).orElse(null);
        List<AgentMessage> messages = activeContextMessages(
                messageStore.findMessages(task.sessionId(), SESSION_CONTEXT_MESSAGE_LIMIT),
                session);
        List<TodoItem> todos = todoStore == null ? List.of() : todoStore.listTodoItems(task.sessionId(), "", 200);
        attachResumeCheckpoint(task, todos);
        if (memoryContextBuilder != null && session != null) {
            var snapshot = memoryContextBuilder.build(task, session, messages, todos);
            if (snapshot != null && snapshot.context() != null && !snapshot.context().isBlank()) {
                // 统一记忆上下文包含短期会话、Todo 状态和长期记忆命中，后续模型只读取这一份。
                task.metadata().put(MEMORY_CONTEXT_METADATA_KEY, preview(snapshot.context(), SESSION_CONTEXT_CHAR_LIMIT));
                task.metadata().put("runtime.memoryHitCount", String.valueOf(snapshot.hits() == null ? 0 : snapshot.hits().size()));
            }
        }
        boolean compactMode = session != null
                && "compact".equalsIgnoreCase(session.metadata().get(CONTEXT_MODE_KEY))
                && session.summary() != null
                && !session.summary().isBlank();
        if (messages.isEmpty() && !compactMode) {
            return;
        }
        StringBuilder context = new StringBuilder();
        if (compactMode) {
            // 压缩摘要代表 activeFrom 之前的有效任务状态，避免继续塞入大量旧轮次原文。
            context.append("summary: ").append(preview(session.summary(), 3000)).append("\n");
        }
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

    private List<AgentMessage> activeContextMessages(List<AgentMessage> messages, AgentSession session) {
        if (messages == null || messages.isEmpty() || session == null) {
            return messages == null ? List.of() : messages;
        }
        String activeFrom = session.metadata().get(CONTEXT_ACTIVE_FROM_KEY);
        if (activeFrom == null || activeFrom.isBlank()) {
            return messages;
        }
        try {
            Instant activeFromInstant = Instant.parse(activeFrom);
            return messages.stream()
                    .filter(message -> !message.createdAt().isBefore(activeFromInstant))
                    .toList();
        } catch (RuntimeException ignored) {
            return messages;
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

    private TokenUsageSummary aggregateTokenUsage(String scopeType, String scopeId, List<AgentEvent> events) {
        int callCount = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        Map<String, TokenUsageSummary.Breakdown> byModel = new LinkedHashMap<>();
        Map<String, TokenUsageSummary.Breakdown> byPhase = new LinkedHashMap<>();

        for (AgentEvent event : events) {
            if (!"llm.call".equals(event.type())) {
                continue;
            }
            Map<String, String> details = event.details();
            int prompt = parseInt(details.get("promptTokens"));
            int completion = parseInt(details.get("completionTokens"));
            int total = parseInt(details.get("totalTokens"));
            if ((prompt == 0 && completion == 0 && total == 0) && details.containsKey("responseJson")) {
                TokenUsageNumbers recovered = recoverTokenUsage(details.get("responseJson"));
                prompt = recovered.promptTokens();
                completion = recovered.completionTokens();
                total = recovered.totalTokens();
            }
            callCount++;
            promptTokens += prompt;
            completionTokens += completion;
            totalTokens += total;
            // model/phase 维度用于管理台快速定位哪个阶段、哪个模型消耗较高。
            byModel.computeIfAbsent(emptyToUnknown(details.get("model")), key -> new TokenUsageSummary.Breakdown())
                    .add(prompt, completion, total);
            byPhase.computeIfAbsent(emptyToUnknown(details.get("phase")), key -> new TokenUsageSummary.Breakdown())
                    .add(prompt, completion, total);
        }

        return new TokenUsageSummary(scopeType, scopeId, callCount, promptTokens, completionTokens, totalTokens, byModel, byPhase);
    }

    /**
     * 从模型原始响应中恢复 token 用量。
     * 早期脱敏规则会把 promptTokens 这类字段误判为密钥并写成 ***，但 responseJson 里通常还保留 usage。
     */
    private TokenUsageNumbers recoverTokenUsage(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return new TokenUsageNumbers(0, 0, 0);
        }
        return new TokenUsageNumbers(
                findJsonInt(PROMPT_TOKENS_PATTERN, responseJson),
                findJsonInt(COMPLETION_TOKENS_PATTERN, responseJson),
                findJsonInt(TOTAL_TOKENS_PATTERN, responseJson));
    }

    /**
     * 从 JSON 字符串中读取指定数字字段；只用于 token usage 兜底，不承担通用 JSON 解析职责。
     */
    private int findJsonInt(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? parseInt(matcher.group(1)) : 0;
    }

    /**
     * token 用量三元组。
     *
     * @param promptTokens prompt token 数。
     * @param completionTokens completion token 数。
     * @param totalTokens 总 token 数。
     */
    private record TokenUsageNumbers(int promptTokens, int completionTokens, int totalTokens) {
    }

    private void saveEvent(AgentTask task, String level, String type, String message, Map<String, String> details) {
        AgentRuntimeInterceptorContext context = interceptorContext("event", type, message, task);
        Map<String, String> interceptedDetails = applyBeforeEvent(context, details);
        AgentEvent event = new AgentEvent(UUID.randomUUID().toString(), task.sessionId(), task.id(), level, type, message, interceptedDetails);
        eventStore.saveEvent(event);
        applyAfterEvent(context, interceptedDetails);
    }

    private void saveApprovalEvent(AgentTask task) {
        Map<String, String> details = new LinkedHashMap<>();
        // 只记录审批相关白名单字段，避免把用户自定义 metadata 或密钥写入任务事件。
        details.put("allowHighRiskTools", nullToEmpty(task.metadata().get("allowHighRiskTools")));
        details.put("approvedToolIds", nullToEmpty(task.metadata().get("approvedToolIds")));
        details.put("approvalMode", nullToEmpty(task.metadata().get("approvalMode")));
        details.put("toolPermissionMode", nullToEmpty(task.metadata().get("toolPermissionMode")));
        saveEvent(task, "DEBUG", "task.approval", "任务审批元数据", details);
        log.debug("agent task approval metadata taskId={} allowHighRiskTools={} approvalMode={} approvedToolIds={}",
                task.id(), details.get("allowHighRiskTools"), details.get("approvalMode"), details.get("approvedToolIds"));
    }

    private void saveResumeRequestEvent(AgentTask task) {
        if (!"true".equalsIgnoreCase(nullToEmpty(task.metadata().get("runtime.resumeRequest")))) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resumeFromTaskId", nullToEmpty(task.metadata().get("runtime.resumeFromTaskId")));
        details.put("resumeFromStatus", nullToEmpty(task.metadata().get("runtime.resumeFromStatus")));
        details.put("todoId", nullToEmpty(task.metadata().get("runtime.resumeTodoId")));
        details.put("todoOrder", nullToEmpty(task.metadata().get("runtime.resumeTodoOrder")));
        details.put("todoTitle", nullToEmpty(task.metadata().get("runtime.resumeTodoTitle")));
        details.put("todoStatus", nullToEmpty(task.metadata().get("runtime.resumeTodoStatus")));
        details.put("resumeMode", nullToEmpty(task.metadata().get("runtime.resumeMode")));
        details.put("resumeInstruction", nullToEmpty(task.metadata().get("runtime.resumeInstruction")));
        // 恢复事件是用户可见审计点，说明本任务不是新规划，而是接续之前未完成的 Todo。
        saveEvent(task, "INFO", "task.resume_requested", "任务继续执行请求", details);
        emit("task.resume_requested", task.id(), "任务继续执行请求", details);
    }

    private Map<String, String> toolEventDetails(AgentTask task, AgentStep step, ToolCall call, AgentTool tool, String output, String error, long elapsedMs, long startedAt) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("stepId", step.id());
        details.put("toolId", call.toolId());
        details.put("status", step.status().name());
        details.put("toolKind", toolKind(call.toolId()));
        // riskLevel 使用本次调用参数的动态结果，管理台和审计日志看到的是实际风险，而不是工具静态默认值。
        details.put("riskLevel", nullToEmpty(tool.riskLevel(call)));
        details.put("approvalMode", nullToEmpty(task.metadata().get("approvalMode")));
        details.put("toolPermissionMode", nullToEmpty(task.metadata().get("toolPermissionMode")));
        details.put("inputPreview", sanitizeText("arguments", preview(call.arguments().toString())));
        details.put("arguments", sanitizeText("arguments", call.arguments().toString()));
        // 事件里同时保存完整受控输出和预览：聊天流只用预览，详情展开时可按 stepId 读取完整结果。
        details.put("output", sanitizeText("output", preview(output, 50000)));
        details.put("outputPreview", sanitizeText("output", preview(output, 4000)));
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
        List<TodoItem> items = selectRelevantTodos(task, todoStore.listTodoItems(task.sessionId(), "", 200));
        return items.stream()
                .filter(item -> item.itemOrder() == order)
                .findFirst()
                .orElse(null);
    }

    static List<TodoItem> selectRelevantTodos(AgentTask task, List<TodoItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String planId = task == null ? "" : nullToEmptyStatic(task.metadata().get("plan.id"));
        if (!planId.isBlank()) {
            List<TodoItem> planItems = items.stream()
                    .filter(item -> planId.equals(item.metadata().get("planId")))
                    .toList();
            if (!planItems.isEmpty()) {
                return deduplicateTodos(planItems);
            }
        }
        String taskId = task == null ? "" : nullToEmptyStatic(task.id());
        if (!taskId.isBlank()) {
            List<TodoItem> currentTaskItems = items.stream()
                    .filter(item -> taskId.equals(item.taskId()))
                    .toList();
            if (!currentTaskItems.isEmpty()) {
                return deduplicateTodos(currentTaskItems);
            }
        }
        String activeTaskId = items.stream()
                .filter(DefaultAgentRuntime::isResumableTodo)
                // 会话里存在多个历史计划时，恢复必须从最近一组未完成 Todo 开始，不能取最早任务。
                .max(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
        if (activeTaskId.isBlank()) {
            return List.of();
        }
        return deduplicateTodos(items.stream()
                .filter(item -> activeTaskId.equals(item.taskId()))
                .toList());
    }

    private static List<TodoItem> deduplicateTodos(List<TodoItem> items) {
        Map<Integer, TodoItem> latestByOrder = new LinkedHashMap<>();
        for (TodoItem item : items) {
            TodoItem current = latestByOrder.get(item.itemOrder());
            if (current == null || item.updatedAt().isAfter(current.updatedAt())) {
                latestByOrder.put(item.itemOrder(), item);
            }
        }
        return latestByOrder.values().stream()
                .sorted(Comparator.comparingInt(TodoItem::itemOrder))
                .toList();
    }

    private static String nullToEmptyStatic(String value) {
        return value == null ? "" : value;
    }

    private boolean isIncompleteTodo(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase(Locale.ROOT);
        return "pending".equals(status) || "running".equals(status);
    }

    private static boolean isResumableTodo(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase(Locale.ROOT);
        return "pending".equals(status) || "running".equals(status) || "failed".equals(status);
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

    private AgentRequest applyBeforeRequest(AgentRequest request) {
        AgentRequest current = request;
        for (AgentRuntimeInterceptor interceptor : runtimeInterceptors) {
            // 请求级拦截器在任务落库前执行，用于统一归一化 input 和 metadata。
            AgentRequest next = interceptor.beforeRequest(current);
            if (next != null) {
                current = next;
            }
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

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void attachResumeCheckpoint(AgentTask task, List<TodoItem> todos) {
        List<TodoItem> activeItems = selectRelevantTodos(task, todos);
        if (activeItems.isEmpty() || activeItems.stream().noneMatch(DefaultAgentRuntime::isResumableTodo)) {
            return;
        }
        TodoItem current = activeItems.stream()
                .filter(item -> "running".equalsIgnoreCase(item.status()))
                .findFirst()
                .or(() -> activeItems.stream().filter(item -> "failed".equalsIgnoreCase(item.status())).findFirst())
                .orElseGet(() -> activeItems.stream()
                        .filter(this::isIncompleteTodo)
                        .findFirst()
                        .orElse(null));
        if (current == null) {
            return;
        }
        String activePlanTaskId = activeItems.get(0).taskId();
        String checkpoint = "resumeFromTaskId=" + activePlanTaskId + "\n"
                + "currentTodoOrder=" + current.itemOrder() + "\n"
                + "currentTodoId=" + current.id() + "\n"
                + "currentTodoTitle=" + current.title() + "\n"
                + "remainingTodos=\n" + formatTodoCheckpoint(activeItems);
        task.metadata().put("runtime.resumeFromTaskId", activePlanTaskId);
        task.metadata().put("runtime.resumeTodoId", current.id());
        task.metadata().put("runtime.resumeTodoOrder", String.valueOf(current.itemOrder()));
        task.metadata().put("runtime.resumeTodoTitle", nullToEmpty(current.title()));
        task.metadata().put("runtime.resumeTodoStatus", nullToEmpty(current.status()));
        task.metadata().put("runtime.resumeMode", resumeMode(current));
        task.metadata().put("runtime.resumeInstruction", resumeInstruction(current));
        task.metadata().put("runtime.resumeCheckpoint", preview(checkpoint, 3000));
    }

    private String resumeMode(TodoItem current) {
        String status = current.status() == null ? "" : current.status().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "failed" -> "retry-failed-todo";
            case "running" -> "continue-running-todo";
            case "pending" -> "start-pending-todo";
            default -> "resume-todo";
        };
    }

    private String resumeInstruction(TodoItem current) {
        String status = current.status() == null ? "" : current.status().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "failed" -> "先复盘该 Todo 失败原因和最近工具输出，再决定重试或修正方案";
            case "running" -> "从当前 running Todo 接着执行，不要重新创建计划";
            case "pending" -> "从第一个 pending Todo 开始执行";
            default -> "按恢复点继续执行";
        };
    }

    private String formatTodoCheckpoint(List<TodoItem> items) {
        StringBuilder builder = new StringBuilder();
        for (TodoItem item : items) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(item.itemOrder())
                    .append(". [").append(item.status()).append("] ")
                    .append(item.title())
                    .append(" id=").append(item.id());
        }
        return builder.toString();
    }

    private void emitResumeCheckpoint(AgentTask task) {
        String todoId = task.metadata().get("runtime.resumeTodoId");
        if (todoId == null || todoId.isBlank()) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resumeFromTaskId", nullToEmpty(task.metadata().get("runtime.resumeFromTaskId")));
        details.put("todoId", todoId);
        details.put("todoOrder", nullToEmpty(task.metadata().get("runtime.resumeTodoOrder")));
        details.put("todoTitle", nullToEmpty(task.metadata().get("runtime.resumeTodoTitle")));
        details.put("todoStatus", nullToEmpty(task.metadata().get("runtime.resumeTodoStatus")));
        details.put("resumeMode", nullToEmpty(task.metadata().get("runtime.resumeMode")));
        details.put("resumeInstruction", nullToEmpty(task.metadata().get("runtime.resumeInstruction")));
        // 恢复事件直接携带 checkpoint，前端运行中也能展示恢复依据，不必等任务结束后再补拉接口。
        details.put("checkpoint", nullToEmpty(task.metadata().get("runtime.resumeCheckpoint")));
        saveEvent(task, "INFO", "task.resume_checkpoint", "任务恢复点", details);
        emit("task.resume_checkpoint", task.id(), "任务恢复点", details);
    }

    private String emptyToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
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
