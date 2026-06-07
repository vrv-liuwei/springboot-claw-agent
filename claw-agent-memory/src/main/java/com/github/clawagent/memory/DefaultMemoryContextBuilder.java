package com.github.clawagent.memory;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryContextSnapshot;
import com.github.clawagent.core.MemoryHitLog;
import com.github.clawagent.core.MemorySearchHit;
import com.github.clawagent.core.MemorySearchRequest;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.spi.MemoryContextBuilder;
import com.github.clawagent.spi.MemoryProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认记忆上下文构建器。
 * <p>
 * 每轮只注入短期上下文和 topK active 长期记忆，不把全部历史或 pending 记忆塞给模型。
 * </p>
 */
public class DefaultMemoryContextBuilder implements MemoryContextBuilder {
    private static final int RECENT_MESSAGE_LIMIT = 20;
    private static final int RECENT_MESSAGE_CHARS = 1_000;
    private static final int TODO_LIMIT = 20;
    private static final int RETRIEVED_MEMORY_TOP_K = 8;
    private static final int CONTEXT_CHAR_LIMIT = 12_000;

    /** 当前启用的记忆 provider；默认使用第一个 provider。 */
    private final List<MemoryProvider> providers;

    /**
     * @param providers 可用记忆 provider。
     */
    public DefaultMemoryContextBuilder(List<MemoryProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    @Override
    public MemoryContextSnapshot build(AgentTask task, AgentSession session, List<AgentMessage> recentMessages, List<TodoItem> todoItems) {
        String userId = normalizeUserId(task.userId());
        String sessionId = task.sessionId();
        List<MemorySearchHit> hits = searchLongTermMemory(task, session, userId);
        StringBuilder context = new StringBuilder();
        appendSessionSummary(context, session);
        appendRecentMessages(context, recentMessages);
        appendTodos(context, todoItems);
        appendLongTermMemory(context, hits);
        String value = preview(context.toString(), CONTEXT_CHAR_LIMIT);
        recordHits(userId, sessionId, task.id(), hits);
        return new MemoryContextSnapshot(userId, sessionId, task.id(), value, hits,
                Map.of("hitCount", String.valueOf(hits.size())));
    }

    private List<MemorySearchHit> searchLongTermMemory(AgentTask task, AgentSession session, String userId) {
        MemoryProvider provider = primaryProvider();
        if (provider == null || task.input() == null || task.input().isBlank()) {
            return List.of();
        }
        List<String> scopes = new ArrayList<>(List.of("global"));
        if (session != null && session.channelId() != null && !session.channelId().isBlank()) {
            scopes.add("channel");
        }
        if (task.sessionId() != null && !task.sessionId().isBlank()) {
            scopes.add("session");
        }
        // M2 不启用 workspace，也不允许 task 作为长期记忆 scope。
        return provider.search(new MemorySearchRequest(
                userId,
                task.input(),
                scopes,
                "",
                List.of("active"),
                "hybrid",
                RETRIEVED_MEMORY_TOP_K));
    }

    private void appendSessionSummary(StringBuilder context, AgentSession session) {
        if (session == null || session.summary() == null || session.summary().isBlank()) {
            return;
        }
        context.append("会话摘要：\n")
                .append(preview(session.summary(), 1_200))
                .append("\n\n");
    }

    private void appendRecentMessages(StringBuilder context, List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        context.append("近期会话上下文：\n");
        int start = Math.max(0, messages.size() - RECENT_MESSAGE_LIMIT);
        for (int i = start; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            // 短期记忆只截取最近消息摘要，避免旧消息无限挤占上下文。
            context.append(message.role())
                    .append(": ")
                    .append(preview(message.content(), RECENT_MESSAGE_CHARS))
                    .append('\n');
        }
        context.append('\n');
    }

    private void appendTodos(StringBuilder context, List<TodoItem> todoItems) {
        if (todoItems == null || todoItems.isEmpty()) {
            return;
        }
        context.append("当前 Todo 状态：\n");
        todoItems.stream().limit(TODO_LIMIT).forEach(item ->
                context.append("- ")
                        .append(item.itemOrder())
                        .append(". ")
                        .append(item.title())
                        .append("=")
                        .append(item.status())
                        .append('\n'));
        context.append('\n');
    }

    private void appendLongTermMemory(StringBuilder context, List<MemorySearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        context.append("长期记忆命中：\n");
        for (MemorySearchHit hit : hits) {
            // 只注入命中的 active 片段，不注入整份长期记忆库。
            context.append("- [")
                    .append(hit.scopeType())
                    .append("/")
                    .append(hit.type())
                    .append("] ")
                    .append(preview(hit.content(), 600))
                    .append('\n');
        }
        context.append('\n');
    }

    private void recordHits(String userId, String sessionId, String taskId, List<MemorySearchHit> hits) {
        MemoryProvider provider = primaryProvider();
        if (provider == null || hits == null || hits.isEmpty()) {
            return;
        }
        for (MemorySearchHit hit : hits) {
            provider.recordHit(new MemoryHitLog(
                    UUID.randomUUID().toString(),
                    userId,
                    sessionId,
                    taskId,
                    hit.itemId(),
                    hit.chunkId(),
                    hit.score(),
                    Instant.now(),
                    new LinkedHashMap<>()));
        }
    }

    private MemoryProvider primaryProvider() {
        return providers.isEmpty() ? null : providers.get(0);
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
