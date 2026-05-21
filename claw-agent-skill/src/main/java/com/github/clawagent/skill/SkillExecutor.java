package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

/**
 * Skill 执行器接口。
 * 不同执行方式，例如文档、HTTP、脚本，都实现这个接口，SkillAgentTool 只负责路由。
 */
interface SkillExecutor {
    ToolResult execute(SkillExecutionContext context, ToolCall call, AgentContext agentContext);
}
