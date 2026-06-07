package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryContextSnapshot;
import com.github.clawagent.core.TodoItem;

import java.util.List;

/**
 * 单轮模型上下文构建器。
 * <p>
 * 它把短期会话、运行态和长期记忆合并成受预算控制的上下文，避免模型收到全部历史。
 * </p>
 */
public interface MemoryContextBuilder {
    /**
     * 构建本轮任务的记忆上下文。
     *
     * @param task 当前任务。
     * @param session 当前会话。
     * @param recentMessages 最近会话消息。
     * @param todoItems 当前会话 Todo 状态。
     * @return 记忆上下文快照。
     */
    MemoryContextSnapshot build(AgentTask task, AgentSession session, List<AgentMessage> recentMessages, List<TodoItem> todoItems);
}
