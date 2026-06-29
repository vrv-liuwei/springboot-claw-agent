package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

/**
 * 外部 Channel 入站 payload 归一化门面。
 * 平台识别和字段映射通过 ChannelRuntimeAdapter 注册表完成，避免在门面里硬编码平台分支。
 */
public final class ChannelInboundPayloadAdapter {
    private static final ChannelAdapterRegistry DEFAULT_REGISTRY = ChannelAdapterRegistry.builtin(null);

    private ChannelInboundPayloadAdapter() {
    }

    public static ChannelInboundMessage adapt(String channelId, Map<String, Object> payload) {
        return adaptWithResponse(null, channelId, payload).message();
    }

    public static ChannelInboundPayloadResult adaptWithResponse(ChannelDefinition channel, Map<String, Object> payload) {
        return adaptWithResponse(channel, channel == null ? null : channel.id(), payload);
    }

    public static ChannelInboundPayloadResult adaptWithResponse(ChannelDefinition channel, String channelId, Map<String, Object> payload) {
        return adaptWithResponse(DEFAULT_REGISTRY, channel, channelId, payload);
    }

    public static ChannelInboundPayloadResult adaptWithResponse(ChannelAdapterRegistry registry, ChannelDefinition channel, String channelId, Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String explicitType = firstNonBlank(
                channel == null ? "" : channel.type(),
                stringValue(safePayload.get("channelType")),
                stringValue(safePayload.get("channelId")),
                channelId);
        ChannelRuntimeAdapter adapter = registry.find(explicitType)
                .or(() -> registry.detect(safePayload))
                .orElse(null);
        String candidateChannel = firstNonBlank(channelId, stringValue(safePayload.get("channelId")), explicitType, adapter == null ? "" : adapter.type(), "api");
        if (adapter == null) {
            return ChannelInboundPayloadResult.message(new ChannelRuntimeAdapter() {
                @Override
                public String type() {
                    return "api";
                }
            }.genericInbound(candidateChannel, safePayload));
        }
        return adapter.adaptInbound(channel, candidateChannel, safePayload);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
