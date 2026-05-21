package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.AgentResponseGenerator;

import java.util.List;

/**
 * 无模型配置时的兜底回复生成器。
 * 生产环境建议使用 claw-agent-model-spring-ai 中的 LLM 实现，避免只拼接工具输出。
 */
public class ToolOutputResponseGenerator implements AgentResponseGenerator {
    @Override
    public String generate(AgentTask task, List<AgentStep> steps) {
        List<String> outputs = steps.stream()
                .filter(step -> step.output() != null && !step.output().isBlank())
                .map(step -> step.name() + "：" + step.output())
                .toList();
        return outputs.isEmpty()
                ? "ClawAgent 已收到请求，但当前没有模型响应生成器，也没有可用工具输出。"
                : String.join("；", outputs);
    }
}
