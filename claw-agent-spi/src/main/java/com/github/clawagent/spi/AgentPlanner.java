package com.github.clawagent.spi;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;

import java.util.List;

public interface AgentPlanner {
    List<ToolCall> plan(AgentTask task);
}
