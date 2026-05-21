package com.github.clawagent.spi;

import java.util.List;

/**
 * 支持 token/chunk 级流式输出的模型客户端。
 */
public interface StreamingModelClient extends ModelClient {
    String chatStream(List<ChatMessage> messages, ChatOptions options, ChatStreamCallback callback);
}
