package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.intent.PendingActionResult;
import com.github.clawagent.intent.PendingActionService;
import com.github.clawagent.intent.PendingActionType;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.server.dto.PlanCreateRequest;
import com.github.clawagent.server.dto.PlanReviseRequest;
import com.github.clawagent.server.dto.PlanRevisionSummaryView;
import com.github.clawagent.server.dto.PlanRunRequest;
import com.github.clawagent.server.dto.PlanTemplateView;
import com.github.clawagent.server.security.ApiTokenAuthInterceptor;
import com.github.clawagent.server.service.AppWorkspaceService;
import com.github.clawagent.server.service.PlanService;
import com.github.clawagent.server.service.TaskPolicyEnrichmentService;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.ChatStreamCallback;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PlanController 提供计划模式 API，执行阶段仍复用 Runtime 和现有工具审批链路。
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {
    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    private final PlanService planService;
    private final com.github.clawagent.runtime.AgentRuntime runtime;
    private final KnowledgeService knowledgeService;
    private final AppWorkspaceService appWorkspaceService;
    private final PendingActionService pendingActionService;
    private final TaskPolicyEnrichmentService taskPolicyEnrichmentService;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public PlanController(PlanService planService,
                          com.github.clawagent.runtime.AgentRuntime runtime,
                          KnowledgeService knowledgeService,
                          AppWorkspaceService appWorkspaceService,
                          PendingActionService pendingActionService,
                          TaskPolicyEnrichmentService taskPolicyEnrichmentService) {
        this.planService = planService;
        this.runtime = runtime;
        this.knowledgeService = knowledgeService;
        this.appWorkspaceService = appWorkspaceService;
        this.pendingActionService = pendingActionService;
        this.taskPolicyEnrichmentService = taskPolicyEnrichmentService;
    }

    @PostMapping
    public PlanDraft create(@RequestBody PlanCreateRequest request) {
        log.info("plan create requested sessionId={} mode={} input={}", request.sessionId(), request.mode(), preview(request.input()));
        Map<String, String> metadata = request.metadata() == null ? Map.of() : request.metadata();
        // 计划生成发生在 Runtime 执行前，必须在这里补齐当前工作区，否则 Planner 无法拿到默认项目目录。
        Map<String, String> workspaceMetadata = appWorkspaceService.enrichWorkspaceMetadata(
                metadata.getOrDefault("workspaceId", ""),
                metadata);
        return planService.create(request.sessionId(), request.input(), request.mode(), request.templateId(), workspaceMetadata);
    }

    @GetMapping("/templates")
    public List<PlanTemplateView> templates() {
        return planService.templates();
    }

    @GetMapping("/{planId}")
    public PlanDraft get(@PathVariable("planId") String planId) {
        return planService.get(planId);
    }

    @GetMapping
    public List<PlanDraft> list(@RequestParam(name = "sessionId", required = false) String sessionId,
                                @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return planService.list(sessionId, limit);
    }

    @GetMapping("/{planId}/revision-summary")
    public PlanRevisionSummaryView revisionSummary(@PathVariable("planId") String planId) {
        return planService.latestRevisionSummary(planId).orElse(null);
    }

    @PostMapping("/{planId}/revise")
    public PlanDraft revise(@PathVariable("planId") String planId, @RequestBody PlanReviseRequest request) {
        return planService.revise(planId, request.feedback());
    }

    @PostMapping("/{planId}/approve")
    public PlanDraft approve(@PathVariable("planId") String planId) {
        PendingActionResult pendingResult = pendingActionService.confirmByTarget(PendingActionType.PLAN_APPROVAL, "", "", planId, "确认执行");
        if (pendingResult.handled()) {
            return planService.get(planId);
        }
        return planService.approve(planId);
    }

    @PostMapping("/{planId}/cancel")
    public PlanDraft cancel(@PathVariable("planId") String planId) {
        pendingActionService.rejectByTarget(PendingActionType.PLAN_APPROVAL, "", "", planId, "用户取消计划");
        return planService.cancel(planId);
    }

    @PostMapping("/{planId}/run/stream")
    public SseEmitter runStream(@PathVariable("planId") String planId,
                                @RequestBody(required = false) PlanRunRequest request,
                                HttpServletRequest servletRequest) {
        PlanDraft approved = planService.ensureRunnable(planId);
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                Map<String, String> metadata = new LinkedHashMap<>(request == null || request.metadata() == null ? Map.of() : request.metadata());
                metadata.putAll(planService.planMetadata(approved));
                Map<String, String> workspaceMetadata = appWorkspaceService.enrichWorkspaceMetadata(metadata.getOrDefault("workspaceId", ""), metadata);
                attachAuthenticatedPrincipal(workspaceMetadata, servletRequest);
                workspaceMetadata = taskPolicyEnrichmentService.enrich(
                        firstNonBlank(request == null ? "" : request.channelId(), "webui"),
                        firstNonBlank(request == null ? "" : request.userId(), "console"),
                        workspaceMetadata);
                AgentRequest agentRequest = new AgentRequest(
                        planService.executionInput(approved),
                        approved.sessionId(),
                        firstNonBlank(request == null ? "" : request.channelId(), "webui"),
                        firstNonBlank(request == null ? "" : request.userId(), "console"),
                        workspaceMetadata);
                AgentResult result = runtime.submitStream(knowledgeService.enrichForModel(agentRequest), new AgentCallback() {
                    @Override
                    public void onEvent(String eventType, String taskId, String message) {
                        onEvent(eventType, taskId, message, Map.of());
                    }

                    @Override
                    public void onEvent(String eventType, String taskId, String message, Map<String, String> details) {
                        if ("task.started".equals(eventType) && taskId != null && !taskId.isBlank()) {
                            planService.markRunning(planId, taskId);
                        }
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("eventType", eventType);
                        payload.put("taskId", nullToEmpty(taskId));
                        payload.put("message", nullToEmpty(message));
                        if (details != null) {
                            payload.putAll(details);
                        }
                        sendSse(emitter, eventType, payload);
                    }
                }, new ChatStreamCallback() {
                    @Override
                    public void onDelta(String content) {
                        String delta = normalizeStreamContent(content);
                        if (!delta.isBlank()) {
                            sendSse(emitter, "llm.delta", Map.of("content", delta));
                        }
                    }

                    @Override
                    public void onComplete(String content) {
                        sendSse(emitter, "llm.completed", Map.of("length", String.valueOf(nullToEmpty(content).length())));
                    }
                });
                String outcome = "COMPLETED".equalsIgnoreCase(result.status().name()) ? "completed" : "failed";
                planService.markDone(planId, outcome);
                sendSse(emitter, "result", Map.of("taskId", result.taskId(), "status", result.status().name(), "answer", result.answer()));
                emitter.complete();
            } catch (RuntimeException e) {
                planService.markBlocked(planId, "tool_failed");
                sendSse(emitter, "error", Map.of("message", nullToEmpty(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void attachAuthenticatedPrincipal(Map<String, String> metadata, HttpServletRequest request) {
        if (metadata == null || request == null) {
            return;
        }
        putAttribute(metadata, "auth.type", request, ApiTokenAuthInterceptor.ATTR_AUTH_TYPE);
        putAttribute(metadata, "localUserId", request, ApiTokenAuthInterceptor.ATTR_USER_ID);
        putAttribute(metadata, "auth.username", request, ApiTokenAuthInterceptor.ATTR_USERNAME);
        if (request.getAttribute(ApiTokenAuthInterceptor.ATTR_TOKEN_ID) != null) {
            putAttribute(metadata, "apiToken.id", request, ApiTokenAuthInterceptor.ATTR_TOKEN_ID);
            putAttribute(metadata, "apiToken.ownerUserId", request, ApiTokenAuthInterceptor.ATTR_USER_ID);
            putAttribute(metadata, "apiToken.ownerUsername", request, ApiTokenAuthInterceptor.ATTR_USERNAME);
            putAttribute(metadata, "apiToken.permissionMode", request, ApiTokenAuthInterceptor.ATTR_TOKEN_PERMISSION_MODE);
            putAttribute(metadata, "apiToken.approvedToolIds", request, ApiTokenAuthInterceptor.ATTR_TOKEN_APPROVED_TOOL_IDS);
            putAttribute(metadata, "apiToken.scopes", request, ApiTokenAuthInterceptor.ATTR_TOKEN_SCOPES);
        }
        if (request.getAttribute(ApiTokenAuthInterceptor.ATTR_DEVICE_ID) != null) {
            putAttribute(metadata, "device.id", request, ApiTokenAuthInterceptor.ATTR_DEVICE_ID);
            putAttribute(metadata, "device.name", request, ApiTokenAuthInterceptor.ATTR_DEVICE_NAME);
            putAttribute(metadata, "device.type", request, ApiTokenAuthInterceptor.ATTR_DEVICE_TYPE);
            putAttribute(metadata, "device.permissionMode", request, ApiTokenAuthInterceptor.ATTR_DEVICE_PERMISSION_MODE);
            putAttribute(metadata, "device.approvedToolIds", request, ApiTokenAuthInterceptor.ATTR_DEVICE_APPROVED_TOOL_IDS);
            putAttribute(metadata, "device.boundUserId", request, ApiTokenAuthInterceptor.ATTR_DEVICE_BOUND_USER_ID);
            putAttribute(metadata, "device.boundUsername", request, ApiTokenAuthInterceptor.ATTR_DEVICE_BOUND_USERNAME);
        }
    }

    private void putAttribute(Map<String, String> metadata, String key, HttpServletRequest request, String attributeName) {
        Object value = request.getAttribute(attributeName);
        if (value != null && !String.valueOf(value).isBlank()) {
            metadata.putIfAbsent(key, String.valueOf(value).trim());
        }
    }

    private String preview(String text) {
        String normalized = nullToEmpty(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
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
