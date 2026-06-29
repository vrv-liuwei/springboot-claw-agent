package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.server.dto.ResumeStateView;
import com.github.clawagent.server.dto.ResumeTaskRequest;
import com.github.clawagent.server.dto.ToolApprovalRequest;
import com.github.clawagent.server.service.AppWorkspaceService;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务提交、恢复、审批和基础回放接口。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskExecutionController {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionController.class);

    private final com.github.clawagent.runtime.AgentRuntime runtime;
    private final TodoStore todoStore;
    private final KnowledgeService knowledgeService;
    private final AppWorkspaceService appWorkspaceService;
    /** 流式任务后台执行池；避免阻塞 Spring MVC 请求线程。 */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public TaskExecutionController(com.github.clawagent.runtime.AgentRuntime runtime,
                                   @Qualifier("todoStore") TodoStore todoStore,
                                   KnowledgeService knowledgeService,
                                   AppWorkspaceService appWorkspaceService) {
        this.runtime = runtime;
        this.todoStore = todoStore;
        this.knowledgeService = knowledgeService;
        this.appWorkspaceService = appWorkspaceService;
    }

    /**
     * 同步提交任务，适合非流式 API 接入。
     */
    @PostMapping("/tasks")
    public AgentResult submit(@RequestBody AgentRequest request) {
        log.info("agent task submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        AgentResult result = runtime.submit(knowledgeService.enrichForModel(enrichWorkspace(request)));
        log.info("agent task submit finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    /**
     * 流式提交任务，向前端推送任务事件和模型增量。
     */
    @PostMapping("/tasks/stream")
    public SseEmitter submitStream(@RequestBody AgentRequest request) {
        log.info("agent task stream submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        return streamAgentRequest(enrichWorkspace(request));
    }

    /**
     * 从已有任务恢复执行，继承原任务上下文并注入恢复点 metadata。
     */
    @PostMapping("/tasks/{taskId}/resume/stream")
    public SseEmitter resumeTaskStream(
            @PathVariable("taskId") String taskId,
            @RequestBody(required = false) ResumeTaskRequest request) {
        AgentTask source = runtime.getTask(taskId);
        ResumeStateView resumeState = buildResumeState(source);
        Map<String, String> metadata = new LinkedHashMap<>(request == null || request.metadata() == null
                ? Map.of()
                : request.metadata());
        inheritResumeMetadata(source, metadata);
        metadata.put("runtime.resumeRequest", "true");
        metadata.put("runtime.resumeFromTaskId", source.id());
        metadata.put("runtime.resumeFromStatus", source.status().name());
        putIfPresent(metadata, "runtime.resumeTodoId", firstNonBlank(source.metadata().get("runtime.resumeTodoId"), resumeState.todoId(), ""));
        putIfPresent(metadata, "runtime.resumeTodoOrder", firstNonBlank(source.metadata().get("runtime.resumeTodoOrder"), resumeState.todoOrder(), ""));
        putIfPresent(metadata, "runtime.resumeTodoTitle", firstNonBlank(source.metadata().get("runtime.resumeTodoTitle"), resumeState.todoTitle(), ""));
        putIfPresent(metadata, "runtime.resumeTodoStatus", firstNonBlank(source.metadata().get("runtime.resumeTodoStatus"), resumeState.todoStatus(), ""));
        putIfPresent(metadata, "runtime.resumeMode", firstNonBlank(source.metadata().get("runtime.resumeMode"), resumeState.resumeMode(), ""));
        putIfPresent(metadata, "runtime.resumeInstruction", firstNonBlank(source.metadata().get("runtime.resumeInstruction"), resumeState.resumeInstruction(), ""));
        putIfPresent(metadata, "runtime.resumeCheckpoint", firstNonBlank(source.metadata().get("runtime.resumeCheckpoint"), resumeState.checkpoint(), ""));
        String input = resumeInput(source, request == null ? "" : request.input(), resumeState);
        AgentRequest resumeRequest = new AgentRequest(
                input,
                source.sessionId(),
                firstNonBlank(request == null ? "" : request.channelId(), source.channelId(), "webui"),
                firstNonBlank(request == null ? "" : request.userId(), source.userId(), "console"),
                metadata);
        log.info("agent task resume stream received sourceTaskId={} sessionId={} resumeTodoOrder={} input={}",
                taskId, resumeRequest.sessionId(), metadata.get("runtime.resumeTodoOrder"), preview(input));
        return streamAgentRequest(resumeRequest);
    }

    /**
     * 查询任务当前恢复点。
     */
    @GetMapping("/tasks/{taskId}/resume-state")
    public ResumeStateView resumeState(@PathVariable("taskId") String taskId) {
        return buildResumeState(runtime.getTask(taskId));
    }

    @GetMapping("/tasks/{taskId}")
    public AgentTask task(@PathVariable("taskId") String taskId) {
        return runtime.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public AgentTask cancelTask(@PathVariable("taskId") String taskId) {
        log.warn("task cancel requested taskId={}", taskId);
        return runtime.cancelTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/approvals/{stepId}/approve")
    public AgentTask approveToolCall(
            @PathVariable("taskId") String taskId,
            @PathVariable("stepId") String stepId,
            @RequestBody ToolApprovalRequest request) {
        log.warn("task tool approval granted taskId={} stepId={} toolId={}", taskId, stepId, request.toolId());
        return runtime.approveToolCall(taskId, stepId, request.toolId());
    }

    @PostMapping("/tasks/{taskId}/approvals/{stepId}/reject")
    public AgentTask rejectToolCall(
            @PathVariable("taskId") String taskId,
            @PathVariable("stepId") String stepId,
            @RequestBody ToolApprovalRequest request) {
        log.warn("task tool approval rejected taskId={} stepId={} toolId={} reason={}",
                taskId, stepId, request.toolId(), request.reason());
        return runtime.rejectToolCall(taskId, stepId, request.toolId(), request.reason());
    }

    @GetMapping("/tasks/{taskId}/steps")
    public List<AgentStep> steps(@PathVariable("taskId") String taskId) {
        return runtime.getSteps(taskId);
    }

    @GetMapping("/tasks/{taskId}/messages")
    public List<AgentMessage> taskMessages(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.debug("task messages requested taskId={} limit={}", taskId, limit);
        return runtime.getTaskMessages(taskId, limit);
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<AgentEvent> taskEvents(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "todoId", required = false) String todoId,
            @RequestParam(name = "stepId", required = false) String stepId) {
        log.debug("task events requested taskId={} limit={} todoId={} stepId={}", taskId, limit, todoId, stepId);
        List<AgentEvent> events = runtime.getTaskEvents(taskId, limit);
        if (todoId != null && !todoId.isBlank()) {
            events = events.stream()
                    .filter(event -> todoId.equals(event.details().get("todoId")))
                    .toList();
        }
        if (stepId != null && !stepId.isBlank()) {
            events = events.stream()
                    .filter(event -> stepId.equals(event.details().get("stepId")))
                    .toList();
        }
        return events;
    }

    @GetMapping("/tasks/{taskId}/token-usage")
    public TokenUsageSummary taskTokenUsage(@PathVariable("taskId") String taskId) {
        log.debug("task token usage requested taskId={}", taskId);
        return runtime.getTaskTokenUsage(taskId);
    }

    private SseEmitter streamAgentRequest(AgentRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                AgentResult result = runtime.submitStream(knowledgeService.enrichForModel(request), new AgentCallback() {
                            @Override
                            public void onEvent(String eventType, String taskId, String message) {
                                onEvent(eventType, taskId, message, Map.of());
                            }

                            @Override
                            public void onEvent(String eventType, String taskId, String message, Map<String, String> details) {
                                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("eventType", eventType);
                                payload.put("taskId", nullToEmpty(taskId));
                                payload.put("message", nullToEmpty(message));
                                if (details != null) {
                                    payload.putAll(details);
                                }
                                sendSse(emitter, eventType, payload);
                            }
                        },
                        new com.github.clawagent.spi.ChatStreamCallback() {
                            @Override
                            public void onDelta(String content) {
                                String delta = normalizeStreamContent(content);
                                if (!delta.isBlank()) {
                                    // 模型流式协议里可能出现 null 片段，后端先过滤，避免前端聊天窗口出现 nullnull。
                                    sendSse(emitter, "llm.delta", Map.of("content", delta));
                                }
                            }

                            @Override
                            public void onComplete(String content) {
                                sendSse(emitter, "llm.completed", Map.of("length", String.valueOf(nullToEmpty(content).length())));
                            }
                        });
                sendSse(emitter, "result", Map.of(
                        "taskId", result.taskId(),
                        "status", result.status().name(),
                        "answer", result.answer()));
                emitter.complete();
            } catch (RuntimeException e) {
                sendSse(emitter, "error", Map.of("message", nullToEmpty(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private String resumeInput(AgentTask source, String requestedInput, ResumeStateView resumeState) {
        if (requestedInput != null && !requestedInput.isBlank()) {
            return requestedInput.trim();
        }
        String todoOrder = firstNonBlank(source.metadata().get("runtime.resumeTodoOrder"), resumeState.todoOrder(), "");
        String todoTitle = firstNonBlank(source.metadata().get("runtime.resumeTodoTitle"), resumeState.todoTitle(), "");
        String instruction = firstNonBlank(source.metadata().get("runtime.resumeInstruction"), resumeState.resumeInstruction(), "");
        StringBuilder input = new StringBuilder("继续执行当前未完成任务。");
        if (todoOrder != null && !todoOrder.isBlank()) {
            input.append("从第 ").append(todoOrder).append(" 个 Todo 开始继续");
            if (todoTitle != null && !todoTitle.isBlank()) {
                input.append("：").append(todoTitle);
            }
            input.append("。");
        }
        if (instruction != null && !instruction.isBlank()) {
            input.append(instruction).append("。");
        }
        input.append("不要重复执行已完成 Todo，只处理 pending/running/failed 的剩余步骤。");
        return input.toString();
    }

    private ResumeStateView buildResumeState(AgentTask source) {
        List<TodoItem> todos = todoStore == null ? List.of() : todoStore.listTodoItems(source.sessionId(), "", 500);
        boolean resumeEligible = source.status() == TaskStatus.CONTINUATION_REQUIRED
                || hasResumeMetadata(source);
        String planTaskId = firstNonBlank(source.metadata().get("runtime.resumeFromTaskId"), source.id(), activeResumePlanTaskId(todos));
        List<TodoItem> planTodos = todos.stream()
                .filter(item -> planTaskId.equals(item.taskId()))
                .sorted(Comparator.comparingInt(TodoItem::itemOrder))
                .toList();
        List<TodoItem> remaining = planTodos.stream()
                .filter(this::isResumeTodo)
                .toList();
        TodoItem current = selectResumeTodo(source, remaining);
        boolean canResume = resumeEligible && current != null;
        String reason = canResume ? "存在未完成 Todo，可从恢复点继续执行" : "未找到可恢复的未完成 Todo";
        String resumeMode = resumeMode(current);
        String resumeInstruction = resumeInstruction(current);
        String checkpoint = firstNonBlank(source.metadata().get("runtime.resumeCheckpoint"), formatResumeCheckpoint(planTaskId, planTodos), "");
        String projectPath = firstNonBlank(
                source.metadata().get("activeProjectPath"),
                firstNonBlank(source.metadata().get("projectPath"), source.metadata().get("workspace.projectPath"), ""),
                "");
        return new ResumeStateView(
                source.id(),
                canResume,
                source.status().name(),
                reason,
                planTaskId,
                projectPath,
                resumeMode,
                resumeInstruction,
                current == null ? "" : current.id(),
                current == null ? "" : String.valueOf(current.itemOrder()),
                current == null ? "" : nullToEmpty(current.title()),
                current == null ? "" : nullToEmpty(current.status()),
                checkpoint,
                remaining
        );
    }

    private void inheritResumeMetadata(AgentTask source, Map<String, String> target) {
        for (String key : List.of(
                "activeProjectPath",
                "projectPath",
                "workspace.projectPath",
                "cwd",
                "approvalMode",
                "toolPermissionMode",
                "allowHighRiskTools",
                "approvedToolIds",
                "knowledge.enabled",
                "knowledge.documentIds",
                "knowledge.scope",
                "knowledge.intent",
                "attachmentIds",
                "attachmentStoragePaths",
                "attachmentKnowledgeDocumentIds",
                "attachments")) {
            // 继续任务要继承源任务的稳定上下文；本次请求已显式传入的字段不覆盖。
            target.putIfAbsent(key, nullToEmpty(source.metadata().get(key)));
        }
        target.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isBlank());
    }

    private boolean hasResumeMetadata(AgentTask source) {
        return source.metadata().containsKey("runtime.resumeTodoId")
                || source.metadata().containsKey("runtime.resumeCheckpoint")
                || source.metadata().containsKey("runtime.resumeRequest");
    }

    private TodoItem selectResumeTodo(AgentTask source, List<TodoItem> remaining) {
        String metadataTodoId = source.metadata().get("runtime.resumeTodoId");
        if (metadataTodoId != null && !metadataTodoId.isBlank()) {
            Optional<TodoItem> matched = remaining.stream()
                    .filter(item -> metadataTodoId.equals(item.id()))
                    .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        return remaining.stream()
                .filter(item -> "running".equalsIgnoreCase(item.status()))
                .findFirst()
                .or(() -> remaining.stream().filter(item -> "failed".equalsIgnoreCase(item.status())).findFirst())
                .orElseGet(() -> remaining.stream().findFirst().orElse(null));
    }

    private String resumeMode(TodoItem current) {
        if (current == null) {
            return "";
        }
        String status = current.status() == null ? "" : current.status().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "failed" -> "retry-failed-todo";
            case "running" -> "continue-running-todo";
            case "pending" -> "start-pending-todo";
            default -> "resume-todo";
        };
    }

    private String resumeInstruction(TodoItem current) {
        if (current == null) {
            return "";
        }
        String status = current.status() == null ? "" : current.status().toLowerCase(Locale.ROOT);
        return switch (status) {
            case "failed" -> "先复盘该 Todo 失败原因和最近工具输出，再决定重试或修正方案";
            case "running" -> "从当前 running Todo 接着执行，不要重新创建计划";
            case "pending" -> "从第一个 pending Todo 开始执行";
            default -> "按恢复点继续执行";
        };
    }

    private String activeResumePlanTaskId(List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) {
            return "";
        }
        return todos.stream()
                .filter(this::isResumeTodo)
                .min(Comparator.comparing(TodoItem::createdAt))
                .map(TodoItem::taskId)
                .orElse("");
    }

    private boolean isResumeTodo(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase(Locale.ROOT);
        return "pending".equals(status) || "running".equals(status) || "failed".equals(status);
    }

    private String formatResumeCheckpoint(String planTaskId, List<TodoItem> todos) {
        if (todos.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("resumeFromTaskId=").append(planTaskId);
        for (TodoItem item : todos) {
            builder.append("\n")
                    .append(item.itemOrder())
                    .append(". [").append(item.status()).append("] ")
                    .append(item.title())
                    .append(" id=").append(item.id());
        }
        return preview(builder.toString(), 3000);
    }

    private AgentRequest enrichWorkspace(AgentRequest request) {
        Map<String, String> metadata = appWorkspaceService.enrichWorkspaceMetadata(
                request == null ? "" : request.workspaceId(),
                request == null ? Map.of() : request.metadata());
        return new AgentRequest(
                request.input(),
                request.sessionId(),
                request.channelId(),
                request.userId(),
                metadata);
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? fallback : second.trim();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private String preview(String text) {
        return preview(text, 120);
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizeStreamContent(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return content.replaceFirst("(?i)^(null)+", "");
    }
}
