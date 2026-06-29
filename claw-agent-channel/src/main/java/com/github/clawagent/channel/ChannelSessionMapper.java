package com.github.clawagent.channel;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Channel 会话映射器。
 * 外部平台 conversationId 稳定映射到 Agent sessionId，保证群聊/单聊能连续对话。
 */
public class ChannelSessionMapper {
    public String stableSessionId(String channelId, String conversationId) {
        String source = "channel:" + firstNonBlank(channelId, "api") + ":" + firstNonBlank(conversationId, "default");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
