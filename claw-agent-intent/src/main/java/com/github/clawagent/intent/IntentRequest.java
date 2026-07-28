package com.github.clawagent.intent;

import java.util.Map;

/**
 * 意图路由输入。
 * <p>
 * ChannelRouter 将用户文字、会话身份和附件/知识库 metadata 统一封装后交给 IntentRoutingService。
 */
public record IntentRequest(
        String input,
        String sessionId,
        String channelId,
        String userId,
        Map<String, String> metadata
) {
    public IntentRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
