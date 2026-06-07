package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryIntent;

import java.util.List;

/**
 * 记忆意图分类器 SPI。
 * <p>
 * 业务方可以替换为模型分类器、规则分类器或外部记忆服务，Runtime 不绑定具体判断方式。
 * </p>
 */
public interface MemoryIntentClassifier {
    /**
     * 判断当前任务是否应该沉淀为长期记忆候选。
     *
     * @param task 当前任务。
     * @param session 当前会话。
     * @param messages 最近会话消息。
     * @param answer 模型最终回复。
     * @return 记忆意图识别结果。
     */
    MemoryIntent classify(AgentTask task, AgentSession session, List<AgentMessage> messages, String answer);
}
