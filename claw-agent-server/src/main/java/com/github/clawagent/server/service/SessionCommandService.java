package com.github.clawagent.server.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.mcp.McpServerStatus;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.server.dto.SessionCommandViews;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spring.ClawAgentProperties;

/**
 * 斜杠指令后端服务。
 * 它聚合已有 Runtime/Store/MCP/Workspace 能力，避免把命令面板逻辑散落在多个 Controller。
 */
@Service
public class SessionCommandService {
    public static final String CONTEXT_VERSION_KEY = "context.version";
    public static final String CONTEXT_ACTIVE_FROM_KEY = "context.activeFrom";
    public static final String CONTEXT_MODE_KEY = "context.mode";
    public static final String CONTEXT_CLEARED_AT_KEY = "context.clearedAt";
    public static final String CONTEXT_COMPACTED_AT_KEY = "context.compactedAt";
    public static final String CONTEXT_COMPACTED_TASK_ID_KEY = "context.compactedTaskId";
    public static final String CONTEXT_COMPACTION_STRATEGY_KEY = "context.compactionStrategy";

    private static final int CONTEXT_MESSAGE_SCAN_LIMIT = 1000;
    private static final int DEFAULT_COMPACT_LIMIT = 120;

    private final AgentRuntime runtime;
    private final SessionStore sessionStore;
    private final SessionMessageStore messageStore;
    private final TodoStore todoStore;
    private final AgentToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final AppWorkspaceService workspaceService;
    private final ClawAgentProperties properties;
    private final AgentEventStore eventStore;

    public SessionCommandService(
            AgentRuntime runtime,
            @Qualifier("sessionStore") SessionStore sessionStore,
            @Qualifier("sessionMessageStore") SessionMessageStore messageStore,
            @Qualifier("todoStore") TodoStore todoStore,
            AgentToolRegistry toolRegistry,
            McpRegistry mcpRegistry,
            AppWorkspaceService workspaceService,
            ClawAgentProperties properties,
            @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.runtime = runtime;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.todoStore = todoStore;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.eventStore = eventStore;
    }

    public SessionCommandViews.CommandResponse clearContext(String sessionId, SessionCommandViews.ClearRequest request) {
        AgentSession session = runtime.getSession(sessionId);
        Instant now = Instant.now();
        List<AgentMessage> messages = messages(sessionId);
        Map<String, String> metadata = new LinkedHashMap<>(session.metadata());
        int contextVersion = nextContextVersion(metadata);
        metadata.put(CONTEXT_VERSION_KEY, String.valueOf(contextVersion));
        metadata.put(CONTEXT_ACTIVE_FROM_KEY, now.toString());
        metadata.put(CONTEXT_MODE_KEY, "clear");
        metadata.put(CONTEXT_CLEARED_AT_KEY, now.toString());
        // 清上下文代表后续不再使用旧摘要，保留历史展示但不继续作为 compact summary 注入。
        metadata.remove(CONTEXT_COMPACTED_AT_KEY);
        metadata.remove(CONTEXT_COMPACTED_TASK_ID_KEY);
        metadata.remove(CONTEXT_COMPACTION_STRATEGY_KEY);
        AgentSession updated = updateSessionMetadata(session, metadata, session.summary());
        recordSessionEvent(sessionId, "session.context_cleared", "会话模型上下文已清理", Map.of(
                "contextVersion", String.valueOf(contextVersion),
                "contextStartAt", now.toString(),
                "reason", request == null || request.reason() == null ? "" : request.reason(),
                "resetTodo", String.valueOf(request != null && request.resetTodo()),
                "resetFileReview", String.valueOf(request != null && request.resetFileReview())));
        return new SessionCommandViews.CommandResponse(
                updated.id(),
                "clear",
                contextVersion,
                now.toString(),
                "",
                countBefore(messages, now));
    }

    public SessionCommandViews.CommandResponse compactContext(String sessionId, SessionCommandViews.CompactRequest request) {
        int limit = request == null || request.limit() == null ? DEFAULT_COMPACT_LIMIT : Math.max(1, Math.min(request.limit(), 500));
        AgentSession summarized = runtime.summarizeSession(sessionId, limit);
        Instant now = Instant.now();
        List<AgentMessage> messages = messages(sessionId);
        Map<String, String> metadata = new LinkedHashMap<>(summarized.metadata());
        int contextVersion = nextContextVersion(metadata);
        String strategy = request == null || request.strategy() == null || request.strategy().isBlank()
                ? "balanced"
                : request.strategy().trim();
        metadata.put(CONTEXT_VERSION_KEY, String.valueOf(contextVersion));
        metadata.put(CONTEXT_ACTIVE_FROM_KEY, now.toString());
        metadata.put(CONTEXT_MODE_KEY, "compact");
        metadata.put(CONTEXT_COMPACTED_AT_KEY, now.toString());
        metadata.put(CONTEXT_COMPACTION_STRATEGY_KEY, strategy);
        putIfPresent(metadata, CONTEXT_COMPACTED_TASK_ID_KEY, request == null ? "" : request.taskId());
        AgentSession updated = updateSessionMetadata(summarized, metadata, summarized.summary());
        recordSessionEvent(sessionId, "session.context_compacted", "会话上下文已压缩", Map.of(
                "contextVersion", String.valueOf(contextVersion),
                "contextStartAt", now.toString(),
                "strategy", strategy,
                "limit", String.valueOf(limit),
                "taskId", request == null || request.taskId() == null ? "" : request.taskId(),
                "summaryLength", String.valueOf(updated.summary() == null ? 0 : updated.summary().length())));
        return new SessionCommandViews.CommandResponse(
                updated.id(),
                "compact",
                contextVersion,
                now.toString(),
                nullToEmpty(updated.summary()),
                countBefore(messages, now));
    }

    public SessionCommandViews.ContextView context(String sessionId) {
        AgentSession session = runtime.getSession(sessionId);
        List<AgentMessage> messages = messages(sessionId);
        Instant contextStartAt = contextStartAt(session.metadata());
        List<AgentMessage> activeMessages = activeMessages(messages, contextStartAt);
        int summaryChars = estimateChars(session.summary());
        int activeChars = estimateMessageChars(activeMessages);
        int todoChars = estimateTodoChars(sessionId);
        List<SessionCommandViews.ContextSegment> segments = new ArrayList<>();
        boolean compactActive = "compact".equalsIgnoreCase(session.metadata().get(CONTEXT_MODE_KEY))
                && session.summary() != null
                && !session.summary().isBlank();
        segments.add(new SessionCommandViews.ContextSegment("summary", "压缩摘要", compactActive ? 1 : 0, compactActive ? summaryChars : 0, compactActive));
        segments.add(new SessionCommandViews.ContextSegment("recent_messages", "边界后的最近消息", activeMessages.size(), activeChars, !activeMessages.isEmpty()));
        segments.add(new SessionCommandViews.ContextSegment("todo", "当前 Todo 状态", todoStore.listTodoItems(sessionId, "", 200).size(), todoChars, todoChars > 0));
        int estimatedChars = (compactActive ? summaryChars : 0) + activeChars + todoChars;
        return new SessionCommandViews.ContextView(
                sessionId,
                contextVersion(session.metadata()),
                nullToEmpty(session.metadata().get(CONTEXT_ACTIVE_FROM_KEY)),
                nullToEmpty(session.metadata().get(CONTEXT_CLEARED_AT_KEY)),
                nullToEmpty(session.metadata().get(CONTEXT_COMPACTED_AT_KEY)),
                nullToEmpty(session.metadata().get(CONTEXT_COMPACTED_TASK_ID_KEY)),
                compactActive ? nullToEmpty(session.summary()) : "",
                messages.size(),
                activeMessages.size(),
                Math.max(0, messages.size() - activeMessages.size()),
                estimatedChars,
                estimateTokens(estimatedChars),
                runtime.getSessionTokenUsage(sessionId, 1000),
                segments);
    }

    public SessionCommandViews.StatusView status(String sessionId) {
        AgentSession session = runtime.getSession(sessionId);
        List<AgentTask> tasks = runtime.getSessionTasks(sessionId, 20);
        AgentTask currentTask = tasks.stream()
                .max(Comparator.comparing(AgentTask::updatedAt))
                .orElse(null);
        List<TodoItem> todos = todoStore.listTodoItems(sessionId, currentTask == null ? "" : currentTask.id(), 200);
        long openTodos = todos.stream().filter(this::isOpenTodo).count();
        int mcpServerCount = mcpRegistry.list().size();
        int mcpConnectedCount = (int) mcpRegistry.list().stream()
                .filter(item -> item.status() == McpServerStatus.CONNECTED)
                .count();
        return new SessionCommandViews.StatusView(
                sessionId,
                session,
                currentTask,
                workspaceService.currentWorkspace().orElse(null),
                runtime.getSessionTokenUsage(sessionId, 1000),
                properties.getLocal().getPermissionMode(),
                properties.getLocal().getApprovedToolIds().size(),
                mcpServerCount,
                mcpConnectedCount,
                toolRegistry.definitions().size(),
                todos.size(),
                (int) openTodos,
                context(sessionId));
    }

    private AgentSession updateSessionMetadata(AgentSession current, Map<String, String> metadata, String summary) {
        AgentSession updated = new AgentSession(
                current.id(),
                current.title(),
                current.channelId(),
                current.userId(),
                metadata,
                current.createdAt(),
                Instant.now(),
                Instant.now(),
                summary);
        sessionStore.updateSession(updated);
        return updated;
    }

    private List<AgentMessage> messages(String sessionId) {
        return messageStore.findMessages(sessionId, CONTEXT_MESSAGE_SCAN_LIMIT);
    }

    private List<AgentMessage> activeMessages(List<AgentMessage> messages, Instant contextStartAt) {
        if (contextStartAt == null) {
            return messages;
        }
        return messages.stream()
                .filter(message -> !message.createdAt().isBefore(contextStartAt))
                .toList();
    }

    private int countBefore(List<AgentMessage> messages, Instant contextStartAt) {
        return (int) messages.stream()
                .filter(message -> message.createdAt().isBefore(contextStartAt))
                .count();
    }

    private Instant contextStartAt(Map<String, String> metadata) {
        String value = metadata.get(CONTEXT_ACTIVE_FROM_KEY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private int nextContextVersion(Map<String, String> metadata) {
        return contextVersion(metadata) + 1;
    }

    private int contextVersion(Map<String, String> metadata) {
        try {
            return Integer.parseInt(metadata.getOrDefault(CONTEXT_VERSION_KEY, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int estimateMessageChars(List<AgentMessage> messages) {
        return messages.stream().mapToInt(message -> estimateChars(message.content())).sum();
    }

    private int estimateTodoChars(String sessionId) {
        return todoStore.listTodoItems(sessionId, "", 200).stream()
                .mapToInt(todo -> estimateChars(todo.title()) + estimateChars(todo.status()) + 12)
                .sum();
    }

    private int estimateChars(String value) {
        return value == null ? 0 : value.length();
    }

    private int estimateTokens(int chars) {
        return Math.max(0, (int) Math.ceil(chars / 3.5d));
    }

    private boolean isOpenTodo(TodoItem item) {
        String status = item.status() == null ? "" : item.status().toLowerCase();
        return "pending".equals(status) || "running".equals(status) || "failed".equals(status);
    }

    private void recordSessionEvent(String sessionId, String type, String message, Map<String, String> details) {
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), sessionId, "", "INFO", type, message, details));
    }

    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
