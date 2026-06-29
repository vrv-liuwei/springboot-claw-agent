package com.github.clawagent.channel;

import java.util.List;
import java.util.Map;

/**
 * Channel 连通性检查结果。
 * 返回给管理台时只暴露配置状态和错误摘要，不返回 token、secret、webhook 等敏感值。
 */
public record ChannelConnectivityStatus(
        String channelId,
        String channelType,
        boolean ready,
        boolean probedRemote,
        String status,
        String message,
        List<String> missingKeys,
        Map<String, String> details
) {
    public static ChannelConnectivityStatus ready(String channelId, String channelType, boolean probedRemote,
                                                  String message, Map<String, String> details) {
        return new ChannelConnectivityStatus(channelId, channelType, true, probedRemote, "ready", message, List.of(), details);
    }

    public static ChannelConnectivityStatus incomplete(String channelId, String channelType, List<String> missingKeys,
                                                       String message, Map<String, String> details) {
        return new ChannelConnectivityStatus(channelId, channelType, false, false, "incomplete", message, missingKeys, details);
    }

    public static ChannelConnectivityStatus failed(String channelId, String channelType, String message,
                                                   Map<String, String> details) {
        return new ChannelConnectivityStatus(channelId, channelType, false, true, "failed", message, List.of(), details);
    }
}
