package com.github.clawagent.spi;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;

import java.util.List;

/**
 * 支持流式最终回复的 ResponseGenerator。
 */
public interface StreamingAgentResponseGenerator extends AgentResponseGenerator {
    String generateStream(AgentTask task, List<AgentStep> steps, ChatStreamCallback callback);
}
