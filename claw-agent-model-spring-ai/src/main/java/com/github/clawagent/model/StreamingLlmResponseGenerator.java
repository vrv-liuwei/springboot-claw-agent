package com.github.clawagent.model;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ChatStreamCallback;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.StreamingAgentResponseGenerator;
import com.github.clawagent.spi.StreamingModelClient;

import java.util.List;

/**
 * 支持模型 token/chunk 流式输出的最终回复生成器。
 * 模型客户端不支持 StreamingModelClient 时回退到普通非流式生成。
 */
public class StreamingLlmResponseGenerator extends LlmResponseGenerator implements StreamingAgentResponseGenerator {
    private final ModelClient modelClient;
    private final ChatOptions options;

    public StreamingLlmResponseGenerator(ModelClient modelClient, ChatOptions options) {
        super(modelClient, options);
        this.modelClient = modelClient;
        this.options = options;
    }

    @Override
    public String generateStream(AgentTask task, List<AgentStep> steps, ChatStreamCallback callback) {
        if (!(modelClient instanceof StreamingModelClient streamingModelClient)) {
            String content = generate(task, steps);
            callback.onDelta(content);
            callback.onComplete(content);
            return content;
        }
        return streamingModelClient.chatStream(List.of(
                ChatMessage.system("你是 ClawAgent 企业级 Harness Agent。请用中文回答用户。若提供了工具结果，只能基于工具结果和用户问题回答，不要伪造未执行的事实。"),
                ChatMessage.user(buildUserPrompt(task, steps))
        ), options, callback);
    }
}
