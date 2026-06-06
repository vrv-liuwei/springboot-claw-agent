package com.github.clawagent.security;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.ToolExecutionGuard;

import java.util.Arrays;
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
        // 控制台可以选择“允许所有高危工具”，也可以只传入本次显式批准的工具 ID。
        boolean explicitlyApproved = containsApprovedTool(approval, call.toolId());
        boolean allowAllHighRisk = isTruthy(task.metadata().get("allowHighRiskTools"));
        if (explicitlyApproved || allowAllHighRisk) {
            // 高危工具只有在请求元数据明确授权时才允许执行。
            return;
        }
        throw new IllegalStateException("高危工具未审批，已阻止执行：" + call.toolId());
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "on".equals(normalized);
    }

    private boolean containsApprovedTool(String approval, String toolId) {
        if (approval == null || approval.isBlank() || toolId == null || toolId.isBlank()) {
            return false;
        }
        String expected = toolId.trim().toLowerCase(Locale.ROOT);
        // approvedToolIds 可能来自 CSV、JSON 数组或空白分隔字符串，这里统一拆成精确 ID 再匹配。
        String normalized = approval
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('"', ' ')
                .replace('\'', ' ');
        return Arrays.stream(normalized.split("[,;\\s]+"))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }
}
