package com.github.clawagent.server.dto;

import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.server.service.AppWorkspaceService;

import java.util.List;

/**
 * 斜杠指令接口专用视图。
 * 这些对象服务于命令面板，不进入 core 领域模型，避免把 UI 聚合字段写散到多个基础对象里。
 */
public final class SessionCommandViews {
    private SessionCommandViews() {
    }

    /** /clear 请求：只移动模型上下文边界，不物理删除历史消息。 */
    public record ClearRequest(String reason, boolean resetTodo, boolean resetFileReview) {
    }

    /** /compact 请求：复用会话摘要能力，把旧上下文压成 summary。 */
    public record CompactRequest(
            String taskId,
            String strategy,
            Integer limit,
            boolean includeTodos,
            boolean includeFileChanges,
            boolean includeToolSummary,
            boolean includeOpenIssues
    ) {
    }

    /** /clear 和 /compact 的执行结果。 */
    public record CommandResponse(
            String sessionId,
            String action,
            int contextVersion,
            String contextStartAt,
            String summary,
            int affectedMessages
    ) {
    }

    /** 当前上下文组成片段，用于解释后续模型请求会带入哪些内容。 */
    public record ContextSegment(
            String type,
            String label,
            int items,
            int estimatedChars,
            boolean active
    ) {
    }

    /** /context 响应：展示上下文边界、摘要、消息数量和 token 粗估。 */
    public record ContextView(
            String sessionId,
            int contextVersion,
            String contextStartAt,
            String clearedAt,
            String compactedAt,
            String compactedTaskId,
            String summary,
            int totalMessages,
            int activeMessages,
            int inactiveMessages,
            int estimatedContextChars,
            int estimatedContextTokens,
            TokenUsageSummary tokenUsage,
            List<ContextSegment> segments
    ) {
    }

    /** /status 响应：聚合当前会话、最近任务、工作区、权限、MCP、工具和 Todo 概览。 */
    public record StatusView(
            String sessionId,
            AgentSession session,
            AgentTask currentTask,
            AppWorkspaceService.AppWorkspace workspace,
            TokenUsageSummary tokenUsage,
            String permissionMode,
            int approvedToolCount,
            int mcpServerCount,
            int mcpConnectedCount,
            int toolCount,
            int todoTotal,
            int todoOpen,
            ContextView context
    ) {
    }
}
