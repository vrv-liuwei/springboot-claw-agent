package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelInboundMessage;

/**
 * Channel 入站适配结果。
 * 平台 URL 校验这类请求不应该进入 Agent Runtime，需要直接把 responseBody 返回给平台。
 */
public record ChannelInboundPayloadResult(
        ChannelInboundMessage message,
        Object responseBody
) {
    public static ChannelInboundPayloadResult message(ChannelInboundMessage message) {
        return new ChannelInboundPayloadResult(message, null);
    }

    public static ChannelInboundPayloadResult immediate(Object responseBody) {
        return new ChannelInboundPayloadResult(null, responseBody);
    }

    public boolean hasImmediateResponse() {
        return responseBody != null;
    }
}
