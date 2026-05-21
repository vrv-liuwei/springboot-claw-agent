package com.github.clawagent.spi;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;

import java.util.List;

/**
 * AgentResponseGenerator 负责生成最终回复。
 * Planner 只决定“要不要调工具”，最终面向用户的自然语言回答由这里统一生成。
 */
public interface AgentResponseGenerator {
    String generate(AgentTask task, List<AgentStep> steps);
}
