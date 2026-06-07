package com.github.clawagent.spi;

import com.github.clawagent.core.AgentTask;

/**
 * 任务完成后的候选记忆处理入口。
 * Runtime 只负责提交候选任务，具体同步、异步或定时批处理由实现类决定。
 */
public interface MemoryCandidateProcessor {

    /**
     * 接收一轮任务的输入、输出上下文，并决定是否进入候选记忆提炼流程。
     *
     * @param task 已完成的 Agent 任务，包含 userId、sessionId 和原始输入。
     * @param answer 本轮助手最终回复，用于辅助判断是否值得沉淀长期记忆。
     */
    void onTaskCompleted(AgentTask task, String answer);
}
