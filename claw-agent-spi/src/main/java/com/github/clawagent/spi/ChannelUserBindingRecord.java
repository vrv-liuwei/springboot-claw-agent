package com.github.clawagent.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Channel 外部用户到本地用户的绑定记录。
 */
public record ChannelUserBindingRecord(
        String id,
        String channelId,
        String externalUserId,
        String externalUsername,
        String localUserId,
        String localUsername,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> metadata
) {
}
