package com.github.clawagent.channel;

import java.util.Map;

/**
 * 长连接/Stream Channel 的运行状态。
 * 状态摘要只用于管理台和审计，不携带平台密钥、token 或 webhook。
 */
public record ChannelStreamStatus(
        String channelId,
        String channelType,
        String mode,
        String status,
        String message,
        Map<String, String> details
) {
    public static ChannelStreamStatus running(String channelId, String channelType, String mode, String message) {
        return new ChannelStreamStatus(channelId, channelType, mode, "running", message, Map.of());
    }

    public static ChannelStreamStatus stopped(String channelId, String channelType, String mode, String message) {
        return new ChannelStreamStatus(channelId, channelType, mode, "stopped", message, Map.of());
    }

    public static ChannelStreamStatus failed(String channelId, String channelType, String mode, String message) {
        return new ChannelStreamStatus(channelId, channelType, mode, "failed", message, Map.of());
    }

    public static ChannelStreamStatus unsupported(String channelId, String channelType, String mode, String message) {
        return new ChannelStreamStatus(channelId, channelType, mode, "unsupported", message, Map.of());
    }
}
