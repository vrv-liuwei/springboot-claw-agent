package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryItem;

import java.util.List;

/**
 * 候选记忆提炼器。
 * <p>
 * task 只能作为提炼来源，提炼后的记忆必须落到 global/channel/session 等长期 scope。
 * </p>
 */
public interface MemoryExtractor {
    /**
     * 从一次任务和会话上下文里提炼候选记忆。
     *
     * @param task 当前任务。
     * @param session 当前会话。
     * @param messages 最近会话消息。
     * @param answer 模型最终答案。
     * @return 候选记忆。
     */
    List<MemoryItem> extract(AgentTask task, AgentSession session, List<AgentMessage> messages, String answer);
}
