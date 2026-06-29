package com.github.clawagent.channel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Channel 入站消息幂等缓存。
 * <p>
 * IM 平台的事件投递通常是 at-least-once，业务侧必须按平台 messageId 做短期去重。
 */
public class ChannelMessageDeduplicator {
    private final ConcurrentMap<String, Long> recentMessages = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ChannelMessageDeduplicator(Duration ttl) {
        this.ttlMillis = Math.max(1_000L, ttl == null ? Duration.ofMinutes(10).toMillis() : ttl.toMillis());
    }

    /**
     * 标记消息并返回是否重复。空 key 不做去重，避免误伤没有平台 messageId 的真实消息。
     */
    public boolean isDuplicate(String messageKey) {
        if (messageKey == null || messageKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupExpired(now);
        Long existing = recentMessages.putIfAbsent(messageKey.trim(), now);
        return existing != null;
    }

    private void cleanupExpired(long now) {
        for (Map.Entry<String, Long> entry : recentMessages.entrySet()) {
            if (now - entry.getValue() > ttlMillis) {
                recentMessages.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
