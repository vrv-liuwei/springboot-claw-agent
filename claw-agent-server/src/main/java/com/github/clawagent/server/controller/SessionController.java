package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.server.dto.SessionCommandViews;
import com.github.clawagent.server.service.AppWorkspaceService;
import com.github.clawagent.server.service.SessionCommandService;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 会话管理接口。
 * 会话恢复、消息分页和标题更新都集中在这里，任务执行和审查由独立 Controller 承载。
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final AgentRuntime runtime;
    private final SessionStore sessionStore;
    private final TodoStore todoStore;
    private final AppWorkspaceService appWorkspaceService;
    private final SessionCommandService sessionCommandService;

    public SessionController(
            AgentRuntime runtime,
            @Qualifier("sessionStore") SessionStore sessionStore,
            @Qualifier("todoStore") TodoStore todoStore,
            AppWorkspaceService appWorkspaceService,
            SessionCommandService sessionCommandService) {
        this.runtime = runtime;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.appWorkspaceService = appWorkspaceService;
        this.sessionCommandService = sessionCommandService;
    }

    @GetMapping("/assistant")
    public AgentResult assistant(@RequestParam("query") String query) {
        log.info("assistant query received input={}", preview(query));
        AgentResult result = runtime.submit(AgentRequest.userMessage(query));
        log.info("assistant query finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    @PostMapping("/sessions")
    public AgentSession createSession(@RequestBody SessionCreateRequest request) {
        log.info("session create received channelId={} userId={} title={}", request.channelId(), request.userId(), preview(request.title()));
        return runtime.createSession(enrichWorkspace(request));
    }

    @PostMapping("/sessions/id")
    public Map<String, String> createSessionId() {
        String sessionId = runtime.createSessionId();
        log.info("session id allocated sessionId={}", sessionId);
        return Map.of("sessionId", sessionId);
    }

    @DeleteMapping("/sessions")
    public Map<String, Object> clearSessions() {
        log.warn("session clear all requested");
        Map<String, Object> result = runtime.clearAllSessions();
        if (todoStore instanceof AgentDataCleaner cleaner) {
            // TodoStore 可能是独立内存实现；SQLite 场景下重复 delete 空表也是安全的。
            cleaner.clearAllAgentData();
        }
        return result;
    }

    @GetMapping("/sessions")
    public List<AgentSession> sessions(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        log.debug("session list requested limit={}", limit);
        return runtime.listSessions(limit);
    }

    @GetMapping("/sessions/{sessionId}")
    public AgentSession session(@PathVariable("sessionId") String sessionId) {
        return runtime.getSession(sessionId);
    }

    @PatchMapping("/sessions/{sessionId}")
    public AgentSession updateSession(
            @PathVariable("sessionId") String sessionId,
            @RequestBody SessionUpdateRequest request) {
        AgentSession current = runtime.getSession(sessionId);
        String title = firstNonBlank(request.title(), current.title());
        AgentSession updated = new AgentSession(
                current.id(),
                title,
                current.channelId(),
                current.userId(),
                current.metadata(),
                current.createdAt(),
                Instant.now(),
                Instant.now(),
                current.summary()
        );
        sessionStore.updateSession(updated);
        return updated;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable("sessionId") String sessionId) {
        log.warn("session delete requested sessionId={}", sessionId);
        boolean deleted = sessionStore.deleteSession(sessionId);
        return Map.of("success", deleted, "sessionId", sessionId);
    }

    @GetMapping("/sessions/{sessionId}/tasks")
    public List<AgentTask> sessionTasks(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return runtime.getSessionTasks(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<AgentMessage> sessionMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "before", required = false) String before,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        if (before == null || before.isBlank()) {
            // 默认返回最近一页消息，聊天窗口刷新后才能看到最新轮次。
            return runtime.getSessionMessages(sessionId, safeLimit);
        }
        try {
            return runtime.getSessionMessagesBefore(sessionId, Instant.parse(before), safeLimit);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("before 参数必须是 ISO-8601 时间：" + before, e);
        }
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public AgentSession summarizeSession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        log.info("session summary requested sessionId={} limit={}", sessionId, limit);
        return runtime.summarizeSession(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> sessionEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        log.debug("session events requested sessionId={} limit={}", sessionId, limit);
        return runtime.getSessionEvents(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/token-usage")
    public TokenUsageSummary sessionTokenUsage(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        log.debug("session token usage requested sessionId={} limit={}", sessionId, limit);
        return runtime.getSessionTokenUsage(sessionId, limit);
    }

    /**
     * /clear：移动模型上下文起点，保留历史消息用于回放。
     */
    @PostMapping("/sessions/{sessionId}/context/clear")
    public SessionCommandViews.CommandResponse clearContext(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) SessionCommandViews.ClearRequest request) {
        log.info("session context clear requested sessionId={}", sessionId);
        return sessionCommandService.clearContext(sessionId, request);
    }

    /**
     * /compact：把当前历史压缩为会话摘要，后续模型只追加压缩点之后的新消息。
     */
    @PostMapping("/sessions/{sessionId}/context/compact")
    public SessionCommandViews.CommandResponse compactContext(
            @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) SessionCommandViews.CompactRequest request) {
        log.info("session context compact requested sessionId={}", sessionId);
        return sessionCommandService.compactContext(sessionId, request);
    }

    /**
     * /context：查看当前模型上下文边界和估算占用。
     */
    @GetMapping("/sessions/{sessionId}/context")
    public SessionCommandViews.ContextView context(@PathVariable("sessionId") String sessionId) {
        return sessionCommandService.context(sessionId);
    }

    /**
     * /status：聚合当前会话、任务、工作区、权限、MCP、Todo 和上下文状态。
     */
    @GetMapping("/sessions/{sessionId}/runtime-status")
    public SessionCommandViews.StatusView runtimeStatus(@PathVariable("sessionId") String sessionId) {
        return sessionCommandService.status(sessionId);
    }

    private SessionCreateRequest enrichWorkspace(SessionCreateRequest request) {
        Map<String, String> metadata = appWorkspaceService.enrichWorkspaceMetadata(
                request.workspaceId(),
                request.metadata() == null ? Map.of() : request.metadata());
        return new SessionCreateRequest(request.title(), request.channelId(), request.userId(), request.workspaceId(), metadata);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    public record SessionUpdateRequest(String title) {
    }
}
