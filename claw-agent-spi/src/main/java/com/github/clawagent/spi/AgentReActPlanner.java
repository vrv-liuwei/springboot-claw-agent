package com.github.clawagent.spi;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;

import java.util.List;

/**
 * ReAct Planner 扩展接口。
 * 支持 Runtime 把已执行步骤作为 observation 传回 Planner，形成多轮规划。
 */
public interface AgentReActPlanner extends AgentPlanner {
    AgentPlan planNext(AgentTask task, List<AgentStep> observations, int round);
}
