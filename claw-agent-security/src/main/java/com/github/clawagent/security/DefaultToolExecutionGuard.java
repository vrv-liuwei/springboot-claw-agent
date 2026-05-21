package com.github.clawagent.security;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.ToolExecutionGuard;

import java.util.Locale;

/**
 * 默认工具安全拦截器。
 * 当前阶段先提供最小可用的运行时校验：高危工具必须显式审批，低/中风险工具默认放行。
 */
public class DefaultToolExecutionGuard implements ToolExecutionGuard {
    @Override
    public void check(AgentTask task, AgentTool tool, ToolCall call) {
        String risk = tool.definition().riskLevel();
        if (!"high".equalsIgnoreCase(risk)) {
            return;
        }
        String approval = task.metadata().get("approvedToolIds");
        boolean explicitlyApproved = approval != null && approval.toLowerCase(Locale.ROOT).contains(call.toolId().toLowerCase(Locale.ROOT));
        boolean allowAllHighRisk = "true".equalsIgnoreCase(task.metadata().get("allowHighRiskTools"));
        if (explicitlyApproved || allowAllHighRisk) {
            // 高危工具只有在请求元数据明确授权时才允许执行。
            return;
        }
        throw new IllegalStateException("高危工具未审批，已阻止执行：" + call.toolId());
    }
}
