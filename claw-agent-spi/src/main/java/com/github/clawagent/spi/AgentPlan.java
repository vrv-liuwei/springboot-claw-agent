package com.github.clawagent.spi;

import com.github.clawagent.core.ToolCall;

import java.util.List;

/**
 * Agent 单轮规划结果。
 * ReAct Planner 使用它表达“继续调用工具”或“已经可以给最终答案”。
 */
public record AgentPlan(
        /** 本轮需要执行的工具调用。 */
        List<ToolCall> calls,
        /** 是否已经完成，不再需要继续调用工具。 */
        boolean finished,
        /** finished=true 时可直接返回的最终答案。 */
        String finalAnswer) {
    public AgentPlan {
        calls = calls == null ? List.of() : List.copyOf(calls);
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
    }

    public static AgentPlan calls(List<ToolCall> calls) {
        return new AgentPlan(calls, false, "");
    }

    public static AgentPlan finalAnswer(String answer) {
        return new AgentPlan(List.of(), true, answer);
    }
}
