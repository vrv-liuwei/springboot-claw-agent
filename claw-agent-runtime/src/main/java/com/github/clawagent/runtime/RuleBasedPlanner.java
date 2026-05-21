package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M1 的规则规划器。
 * 它不是最终智能规划器，只用于验证 Task/Step/Tool/Audit 运行骨架是否正确。
 */
public class RuleBasedPlanner implements AgentPlanner {
    @Override
    public List<ToolCall> plan(AgentTask task) {
        List<ToolCall> calls = new ArrayList<>();
        String input = task.input();

        extractCity(input).ifPresent(city -> calls.add(new ToolCall("builtin.weather", Map.of("city", city))));

        if (input.contains("时间") || input.toLowerCase().contains("time")) {
            calls.add(new ToolCall("builtin.time", Map.of()));
        }

        return calls;
    }

    private java.util.Optional<String> extractCity(String input) {
        for (String city : List.of("北京", "上海", "深圳")) {
            if (input.contains(city)) return java.util.Optional.of(city);
        }
        return java.util.Optional.empty();
    }

}
