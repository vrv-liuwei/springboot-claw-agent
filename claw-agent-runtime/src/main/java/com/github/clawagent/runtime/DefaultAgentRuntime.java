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
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AgentPlan;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.AgentReActPlanner;
import com.github.clawagent.spi.AgentResponseGenerator;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.LlmCallTrace;
import com.github.clawagent.spi.LlmTraceContext;
import com.github.clawagent.spi.MemoryPromoter;
import com.github.clawagent.spi.ChatStreamCallback;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.SessionSummarizer;
import com.github.clawagent.spi.StreamingAgentResponseGenerator;
import com.github.clawagent.spi.TaskStore;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ToolExecutionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DefaultAgentRuntime 是 M1 的核心执行链路。
 * 这里刻意把“规划、工具查找、步骤落库、错误处理”显式拆开，便于后续替换为 LLM Planner 和审批流。
 */
public class DefaultAgentRuntime implements AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);
    /** ReAct 最大轮次，避免模型反复规划导致请求长时间不返回。 */
    private static final int MAX_REACT_ROUNDS = 5;

    private final AgentPlanner planner;
    private final AgentResponseGenerator responseGenerator;
    private final AgentToolRegistry toolRegistry;
    private final TaskStore taskStore;
    private final SessionStore sessionStore;
    private final SessionMessageStore messageStore;
    private final SessionSummarizer sessionSummarizer;
    private final List<MemoryPromoter> memoryPromoters;
    private final AgentEventStore eventStore;
    private final List<ToolExecutionGuard> toolGuards;
    private final List<AgentCallback> callbacks;
    /** 当前提交请求的临时回调列表，用于单次任务流式推送运行事件。 */
    private final ThreadLocal<List<AgentCallback>> activeCallbacks = new ThreadLocal<>();

    public DefaultAgentRuntime(AgentPlanner planner, AgentResponseGenerator responseGenerator, AgentToolRegistry toolRegistry, TaskStore taskStore, SessionStore sessionStore, SessionMessageStore messageStore, SessionSummarizer sessionSummarizer, List<MemoryPromoter> memoryPromoters, AgentEventStore eventStore, List<ToolExecutionGuard> toolGuards, List<AgentCallback> callbacks) {
        this.planner = planner;
        this.responseGenerator = responseGenerator;
        this.toolRegistry = toolRegistry;
        this.taskStore = taskStore;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.sessionSummarizer = sessionSummarizer;
        this.memoryPromoters = memoryPromoters == null ? List.of() : List.copyOf(memoryPromoters);
        this.eventStore = eventStore;
        this.toolGuards = toolGuards == null ? List.of() : List.copyOf(toolGuards);
        this.callbacks = new ArrayList<>(callbacks);
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
            return new AgentResult(task.id(), answer, task.status());
        } catch (RuntimeException e) {
            saveLlmTraces(task, "error");
            task.markStatus(TaskStatus.FAILED);
            taskStore.updateTask(task);
            saveEvent(task, "ERROR", "task.failed", "任务失败", Map.of(
                    "status", task.status().name(),
                    "error", nullToEmpty(e.getMessage())));
            emit("task.failed", task.id(), e.getMessage());
            log.error("agent task failed error={}", e.getMessage(), e);
            return new AgentResult(task.id(), "执行失败：" + e.getMessage(), task.status());
        } finally {
            MDC.clear();
            activeCallbacks.remove();
        }
    }

    private String runPlanner(AgentTask task, List<AgentStep> steps) {
        if (planner instanceof AgentReActPlanner reactPlanner) {
            return runReActPlanner(task, steps, reactPlanner);
        }
        LlmTraceContext.clear();
        List<ToolCall> plannedCalls = planner.plan(task);
        saveLlmTraces(task, "planner");
        log.info("agent planner finished toolCallCount={} calls={}", plannedCalls.size(), plannedCalls);
        log.debug("agent planner calls taskId={} calls={}", task.id(), plannedCalls);
        saveEvent(task, "INFO", "planner.finished", "工具规划完成", Map.of(
                "toolCallCount", String.valueOf(plannedCalls.size()),
                "calls", plannedCalls.toString()));
        executeToolCalls(task, steps, plannedCalls);
        return null;
    }

    private String runReActPlanner(AgentTask task, List<AgentStep> steps, AgentReActPlanner reactPlanner) {
        for (int round = 1; round <= MAX_REACT_ROUNDS; round++) {
            LlmTraceContext.clear();
            AgentPlan plan = reactPlanner.planNext(task, steps, round);
            saveLlmTraces(task, "planner-react-" + round);
            log.info("agent react planner finished round={} finished={} toolCallCount={} calls={}",
                    round, plan.finished(), plan.calls().size(), plan.calls());
            saveEvent(task, "INFO", "planner.react.finished", "ReAct 规划完成", Map.of(
                    "round", String.valueOf(round),
                    "finished", String.valueOf(plan.finished()),
                    "toolCallCount", String.valueOf(plan.calls().size()),
                    "calls", plan.calls().toString()));
            if (plan.finished()) {
                return plan.finalAnswer();
            }
            if (plan.calls().isEmpty()) {
                // 模型没有给出工具调用，也没有给最终答案时，退出 ReAct 循环，交给 ResponseGenerator 收口。
                return null;
            }
            executeToolCalls(task, steps, plan.calls());
        }
        saveEvent(task, "WARN", "planner.react.max_rounds", "ReAct 达到最大轮次", Map.of(
                "maxRounds", String.valueOf(MAX_REACT_ROUNDS)));
        log.warn("agent react planner reached max rounds taskId={} maxRounds={}", task.id(), MAX_REACT_ROUNDS);
        return null;
    }

    private void executeToolCalls(AgentTask task, List<AgentStep> steps, List<ToolCall> calls) {
        for (ToolCall call : calls) {
            executeToolCall(task, steps, call);
        }
    }

    private void executeToolCall(AgentTask task, List<AgentStep> steps, ToolCall call) {
        AgentTool tool = toolRegistry.find(call.toolId())
                .orElseThrow(() -> new IllegalArgumentException("工具不存在：" + call.toolId()));
        AgentStep step = new AgentStep(UUID.randomUUID().toString(), task.id(), StepType.TOOL_CALL, call.toolId(), call.arguments());
        emit("step.started", task.id(), call.toolId());
        log.info("agent tool started stepId={} toolId={} input={}", step.id(), call.toolId(), call.arguments());
        log.debug("agent tool arguments taskId={} stepId={} arguments={}", task.id(), step.id(), call.arguments());
        saveEvent(task, "DEBUG", "tool.started", "工具调用开始", Map.of(
                "stepId", step.id(),
                "toolId", call.toolId(),
                "arguments", call.arguments().toString(),
                "toolKind", toolKind(call.toolId())));

        RuntimeException guardError = checkToolGuards(task, tool, call);
        if (guardError != null) {
            // 拦截失败只影响当前工具步骤，后续由 ResponseGenerator 汇总失败原因给用户。
            step.fail(guardError.getMessage());
            taskStore.saveStep(step);
            steps.add(step);
            saveEvent(task, "WARN", "tool.blocked", "工具调用被安全策略拦截", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "error", nullToEmpty(guardError.getMessage()),
                    "toolKind", toolKind(call.toolId())));
            log.warn("agent tool blocked stepId={} toolId={} error={}", step.id(), call.toolId(), guardError.getMessage());
            emit("step.finished", task.id(), step.status().name());
            return;
        }

        ToolResult result = tool.execute(call, AgentContext.forTask(task));
        if (result.success()) {
            step.succeed(result.content());
            log.info("agent tool succeeded stepId={} toolId={} output={}", step.id(), call.toolId(), preview(result.content()));
            log.debug("agent tool output taskId={} stepId={} output={}", task.id(), step.id(), preview(result.content()));
            saveEvent(task, "DEBUG", "tool.succeeded", "工具调用成功", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "output", nullToEmpty(result.content()),
                    "toolKind", toolKind(call.toolId())));
        } else {
            step.fail(result.content());
            log.warn("agent tool failed stepId={} toolId={} error={}", step.id(), call.toolId(), preview(result.content()));
            saveEvent(task, "WARN", "tool.failed", "工具调用失败", Map.of(
                    "stepId", step.id(),
                    "toolId", call.toolId(),
                    "error", nullToEmpty(result.content()),
                    "toolKind", toolKind(call.toolId())));
        }
        taskStore.saveStep(step);
        steps.add(step);
        emit("step.finished", task.id(), step.status().name());
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
    public AgentTask getTask(String taskId) {
        return taskStore.findTask(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
    }

    @Override
    public List<AgentStep> getSteps(String taskId) {
        return taskStore.findSteps(taskId);
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
        List<AgentCallback> scopedCallbacks = activeCallbacks.get();
        if (scopedCallbacks == null) {
            scopedCallbacks = callbacks;
        }
        scopedCallbacks.forEach(callback -> callback.onEvent(type, taskId, message));
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
                    .orElseGet(() -> createSession(new SessionCreateRequest(
                            titleFromInput(request.input()),
                            request.channelId(),
                            request.userId(),
                            request.metadata())));
        }
        return createSession(new SessionCreateRequest(titleFromInput(request.input()), request.channelId(), request.userId(), request.metadata()));
    }

    private void saveMessage(String sessionId, String taskId, String role, String content) {
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), sessionId, taskId, role, content, java.util.Map.of());
        messageStore.saveMessage(message);
        log.debug("agent message saved sessionId={} taskId={} role={}", sessionId, taskId, role);
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
                    task.id(), phase, trace.model(), trace.statusCode(), trace.promptTokens(), trace.completionTokens(), trace.totalTokens(), trace.requestJson(), trace.responseJson());
        }
    }

    private void saveEvent(AgentTask task, String level, String type, String message, Map<String, String> details) {
        AgentEvent event = new AgentEvent(UUID.randomUUID().toString(), task.sessionId(), task.id(), level, type, message, details);
        eventStore.saveEvent(event);
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
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
