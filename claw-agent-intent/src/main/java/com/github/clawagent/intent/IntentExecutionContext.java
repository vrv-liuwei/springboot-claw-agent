package com.github.clawagent.intent;

import java.util.Map;

/**
 * 意图 handler 执行上下文。
 * <p>
 * handler 通过它读取原始输入、会话身份和路由阶段写入的 metadata。
 */
public record IntentExecutionContext(
        IntentDefinition intent,
        String input,
        String sessionId,
        String channelId,
        String userId,
        Map<String, String> metadata
) {
    public IntentExecutionContext {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
