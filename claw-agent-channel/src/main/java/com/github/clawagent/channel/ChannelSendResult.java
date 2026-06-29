package com.github.clawagent.channel;

import java.util.Map;

/**
 * Channel 出站发送结果。
 */
public record ChannelSendResult(
        boolean sent,
        String status,
        String message,
        Map<String, String> details
) {
    public static ChannelSendResult sent(String message, Map<String, String> details) {
        return new ChannelSendResult(true, "sent", message, details == null ? Map.of() : Map.copyOf(details));
    }

    public static ChannelSendResult failed(String message, Map<String, String> details) {
        return new ChannelSendResult(false, "failed", message, details == null ? Map.of() : Map.copyOf(details));
    }

    public static ChannelSendResult unsupported(String message, Map<String, String> details) {
        return new ChannelSendResult(false, "unsupported", message, details == null ? Map.of() : Map.copyOf(details));
    }
}
