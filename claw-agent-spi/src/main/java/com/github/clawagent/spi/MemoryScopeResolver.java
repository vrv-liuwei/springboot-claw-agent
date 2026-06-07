package com.github.clawagent.spi;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryItem;

/**
 * 记忆范围解析器。
 * <p>
 * 规则优先，模型辅助；当前 M2 只启用 global、channel、session，workspace 仅预留。
 * </p>
 */
public interface MemoryScopeResolver {
    /**
     * 给候选记忆补齐或纠正 scope。
     *
     * @param task 当前任务。
     * @param candidate 候选记忆。
     * @return 带合法 scope 的记忆条目。
     */
    MemoryItem resolve(AgentTask task, MemoryItem candidate);
}
