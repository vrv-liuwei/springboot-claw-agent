package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;

import java.util.List;

/**
 * 长期记忆提升接口。
 * Runtime 在会话摘要生成后调用它，把短期会话信息提升到 Markdown、VectorStore 或其他长期记忆系统。
 */
public interface MemoryPromoter {
    void promoteSessionSummary(AgentSession session, List<AgentMessage> messages);
}
