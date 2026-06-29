package com.github.clawagent.core;

import java.util.Map;

/**
 * 外部 Channel 统一入站消息。
 * 平台 adapter 只负责验签和解包，然后填充该对象交给通用 Channel 路由。
 */
public record ChannelInboundMessage(
        String channelId,
        String externalConversationId,
        String externalUserId,
        String messageType,
        String text,
        Map<String, String> metadata,
        Map<String, Object> rawPayload
) {
}
