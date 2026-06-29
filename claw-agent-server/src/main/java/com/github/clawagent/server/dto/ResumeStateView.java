package com.github.clawagent.server.dto;

import com.github.clawagent.core.TodoItem;

import java.util.List;

/**
 * 任务恢复点视图。
 * 前端刷新历史会话时用它判断是否展示“继续执行”，不再依赖聊天文本正则猜测。
 */
public record ResumeStateView(
        String taskId,
        boolean canResume,
        String status,
        String reason,
        String resumeFromTaskId,
        String projectPath,
        String resumeMode,
        String resumeInstruction,
        String todoId,
        String todoOrder,
        String todoTitle,
        String todoStatus,
        String checkpoint,
        List<TodoItem> remainingTodos
) {
}
