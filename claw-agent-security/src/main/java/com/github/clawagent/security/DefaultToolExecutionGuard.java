package com.github.clawagent.security;

import com.github.clawagent.core.ApprovalPolicy;
import com.github.clawagent.core.ApprovalPolicyResolution;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.ToolExecutionGuard;

/**
 * 默认工具安全拦截器。
 * 当前阶段先提供最小可用的运行时校验：低风险直接放行，高危按模式审批，auto 遇到不确定风险会退回人工确认。
 */
public class DefaultToolExecutionGuard implements ToolExecutionGuard {
    private static final String PROMPT_INJECTION_SUSPECTED_KEY = "security.promptInjectionSuspected";
    private static final String PROMPT_INJECTION_REASON_KEY = "security.promptInjectionReason";

    @Override
    public void check(AgentTask task, AgentTool tool, ToolCall call) {
        String risk = tool.riskLevel(call);
        if (isReadOnlyIsolatedAgent(task) && !"low".equalsIgnoreCase(risk)) {
            // 子 Agent 默认只负责分析和检索，非低风险动作必须回到主 Agent/用户审批链路。
            throw new IllegalStateException("子 Agent 只读隔离已阻止非低风险工具："
                    + call.toolId() + "，riskLevel=" + risk);
        }
        if ("low".equalsIgnoreCase(risk)) {
            return;
        }
        ApprovalPolicyResolution resolution = ApprovalPolicyResolution.fromTaskMetadata(task.metadata());
        ApprovalPolicy policy = resolution.policy();

        if (policy.isExplicitlyApproved(call.toolId())) {
            // 显式批准只对本次工具 ID 生效；疑似注入场景也必须有这一轮明确批准才继续。
            return;
        }
        if (isTruthy(task.metadata().get(PROMPT_INJECTION_SUSPECTED_KEY))) {
            String reason = firstPresent(task.metadata().get(PROMPT_INJECTION_REASON_KEY), "疑似提示词注入风险");
            throw new IllegalStateException("疑似提示词注入，需要用户确认后再执行：" + call.toolId() + "，reason=" + reason);
        }
        if (policy.isFullAccess()) {
            return;
        }
        if ("high".equalsIgnoreCase(risk) && policy.allowsHighRiskAutomatically()) {
            // auto 只自动批准明确高危的工具；不确定风险继续交给用户判断。
            return;
        }
        if ("medium".equalsIgnoreCase(risk) && policy.isAutoApprove()) {
            throw new IllegalStateException("工具风险不确定，需要用户确认：" + call.toolId() + "，riskLevel=" + risk);
        }
        if ("high".equalsIgnoreCase(risk)) {
            throw new IllegalStateException("高危工具未审批，已阻止执行：" + call.toolId() + "，riskLevel=" + risk);
        }
        // 未识别风险等级时按不确定风险处理，避免工具实现写错 riskLevel 后被默认放行。
        throw new IllegalStateException("工具风险等级未知，需要用户确认：" + call.toolId() + "，riskLevel=" + risk);
    }

    private boolean isReadOnlyIsolatedAgent(AgentTask task) {
        String isolation = firstPresent(task.metadata().get("agent.isolation.effective"),
                task.metadata().get("agent.isolation"));
        return "read-only".equalsIgnoreCase(isolation);
    }

    private String firstPresent(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "on".equals(normalized);
    }
}
