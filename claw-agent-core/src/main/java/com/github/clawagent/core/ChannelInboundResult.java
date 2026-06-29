package com.github.clawagent.core;

/**
 * Channel 入站消息提交给 Agent 后的统一响应。
 * HTTP、飞书回调、钉钉回调可以按各自协议再包装这个结果。
 */
public record ChannelInboundResult(
        String channelId,
        String sessionId,
        String taskId,
        String status,
        String answer
) {
}
