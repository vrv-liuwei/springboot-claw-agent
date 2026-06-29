package com.github.clawagent.spi;

import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

/**
 * 外部 IM 平台适配器接口。
 * 内置飞书/钉钉先由 claw-agent-channel 提供 HTTP adapter，独立模块或企业版 adapter 可继续实现该 SPI。
 */
public interface ChannelAdapter {
    String type();

    default boolean supports(String channelType) {
        return channelType != null && channelType.equalsIgnoreCase(type());
    }

    ChannelInboundMessage adaptInbound(String channelId, Map<String, Object> rawPayload);
}
