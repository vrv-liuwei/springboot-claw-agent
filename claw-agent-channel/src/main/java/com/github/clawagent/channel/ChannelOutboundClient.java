package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

/**
 * Channel 出站门面。
 * 具体平台协议由 ChannelRuntimeAdapter 提供，门面只负责参数校验和按 channel.type 分发。
 */
public class ChannelOutboundClient {
    private final ChannelAdapterRegistry adapterRegistry;

    public ChannelOutboundClient() {
        this(ChannelAdapterRegistry.builtin(null));
    }

    public ChannelOutboundClient(ChannelAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    public boolean sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        return sendTextDetailed(channel, sourceMessage, text).sent();
    }

    public ChannelSendResult sendTextDetailed(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        if (channel == null || sourceMessage == null || text == null || text.isBlank()) {
            return ChannelSendResult.failed("Channel、sourceMessage 或文本为空，无法发送。", Map.of("reason", "invalid-request"));
        }
        return adapterRegistry.find(safeType(channel))
                .map(adapter -> adapter.sendText(channel, sourceMessage, text))
                .orElseGet(() -> ChannelSendResult.unsupported(
                        "当前 Channel 类型不支持内置出站回写。",
                        Map.of("channelType", safeType(channel))));
    }

    public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        if (channel == null) {
            return ChannelConnectivityStatus.failed("", "", "Channel 不存在", Map.of());
        }
        return adapterRegistry.find(safeType(channel))
                .map(adapter -> adapter.checkConnectivity(channel))
                .orElseGet(() -> ChannelConnectivityStatus.ready(
                        stringValue(channel.id()),
                        safeType(channel),
                        false,
                        "通用 Channel 仅检查本地配置，不需要外部平台探测。",
                        Map.of("mode", "local-only")));
    }

    private String safeType(ChannelDefinition channel) {
        return channel.type() == null ? "" : channel.type().trim().toLowerCase();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
