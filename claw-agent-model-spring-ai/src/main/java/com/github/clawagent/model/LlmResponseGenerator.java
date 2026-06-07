package com.github.clawagent.model;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.AgentResponseGenerator;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于真实模型的最终回复生成器。
 * 工具结果只作为上下文输入给模型，最终回答不再由 Runtime 直接拼接。
 */
public class LlmResponseGenerator implements AgentResponseGenerator {
    private static final Logger log = LoggerFactory.getLogger(LlmResponseGenerator.class);

    private final ModelClient modelClient;
    private final ChatOptions options;

    public LlmResponseGenerator(ModelClient modelClient, ChatOptions options) {
        this.modelClient = modelClient;
        this.options = options;
    }

    @Override
    public String generate(AgentTask task, List<AgentStep> steps) {
        log.info("llm response generation started taskId={} model={} stepCount={}", task.id(), options.model(), steps.size());
        String content = modelClient.chat(List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user(buildUserPrompt(task, steps))
        ), options);
        log.info("llm response generation finished taskId={} answerLength={}", task.id(), content.length());
        log.debug("llm response content taskId={} content={}", task.id(), content);
        return content.trim();
    }

    protected String systemPrompt() {
        return "你是 ClawAgent 企业级 Harness Agent。请用中文回答用户。若提供了工具结果，只能基于工具结果和用户问题回答，不要伪造未执行的事实。"
                + "Todo 状态展示必须一致：pending/未执行/待执行只能使用灰点或 ⏳，running/执行中使用红点或 🔴，completed/已完成/完成才可以使用 ✅，failed/失败使用 ❌。"
                + "禁止输出“✅ pending”“✅ 未执行”“✅ 待执行”这类互相矛盾的状态。";
    }

    protected String buildUserPrompt(AgentTask task, List<AgentStep> steps) {
        StringBuilder prompt = new StringBuilder();
        String knowledge = LlmAgentPlanner.knowledgeContext(task);
        if (!knowledge.isBlank()) {
            prompt.append("知识库上下文：\n").append(knowledge).append("\n\n");
        }
        String memory = LlmAgentPlanner.memoryContext(task);
        if (!memory.isBlank()) {
            prompt.append("记忆上下文：\n").append(memory).append("\n\n");
        }
        String context = LlmAgentPlanner.sessionContext(task);
        if (!context.isBlank()) {
            prompt.append("近期会话上下文：\n").append(context).append("\n\n");
        }
        prompt.append("用户请求：").append(task.input()).append("\n\n");
        if (steps.isEmpty()) {
            prompt.append("没有执行工具，请直接基于你的模型能力回答。");
            return prompt.toString();
        }

        // 将每个工具步骤压缩为可读上下文，交给模型做最终组织和解释。
        prompt.append("已执行工具结果：\n");
        for (AgentStep step : steps) {
            prompt.append("- tool=").append(step.name())
                    .append(", status=").append(step.status())
                    .append(", input=").append(step.input())
                    .append(", output=").append(step.output())
                    .append(", error=").append(step.error())
                    .append("\n");
        }
        prompt.append("\n请给出最终回答。");
        return prompt.toString();
    }
}
