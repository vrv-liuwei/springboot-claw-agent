package com.github.clawagent.spi;

import java.util.List;

/**
 * 模型客户端 SPI。
 * Runtime 和 Planner 不绑定具体厂商，DeepSeek、OpenAI、本地 Ollama 都可以实现这个接口。
 */
public interface ModelClient {
    String chat(List<ChatMessage> messages, ChatOptions options);
}
