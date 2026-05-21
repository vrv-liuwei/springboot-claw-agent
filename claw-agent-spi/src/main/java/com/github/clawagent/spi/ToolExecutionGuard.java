package com.github.clawagent.spi;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;

/**
 * 工具执行前置拦截器。
 * 用于通道访问控制、审批、风险校验、提示词注入防御等运行时安全能力。
 */
public interface ToolExecutionGuard {
    /**
     * 校验本次工具调用是否允许执行。
     * 如果不允许执行，直接抛出 RuntimeException，Runtime 会把当前 tool step 标记为失败。
     */
    void check(AgentTask task, AgentTool tool, ToolCall call);
}
