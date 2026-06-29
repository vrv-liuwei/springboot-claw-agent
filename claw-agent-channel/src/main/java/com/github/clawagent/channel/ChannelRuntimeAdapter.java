package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

/**
 * Channel 运行时适配器。
 * 内置平台和外部 jar adapter 都通过这个接口接入入站、出站、连通性检查和长连接能力。
 */
public interface ChannelRuntimeAdapter {
    String type();

    default boolean supports(String channelType) {
        return channelType != null && channelType.equalsIgnoreCase(type());
    }

    default boolean detectInbound(Map<String, Object> payload) {
        return false;
    }

    default ChannelInboundPayloadResult adaptInbound(ChannelDefinition channel, String channelId, Map<String, Object> rawPayload) {
        return ChannelInboundPayloadResult.message(genericInbound(channelId, rawPayload));
    }

    default ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        return ChannelSendResult.unsupported("当前 Channel 类型不支持出站回写。", Map.of("channelType", safeType(channel)));
    }

    default ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        return ChannelConnectivityStatus.ready(
                channel == null ? "" : stringValue(channel.id()),
                safeType(channel),
                false,
                "通用 Channel 仅检查本地配置，不需要外部平台探测。",
                Map.of("mode", "local-only"));
    }

    default ChannelStreamHandle startStream(ChannelDefinition channel) throws Exception {
        throw new UnsupportedOperationException("当前 Channel 类型不支持 SDK 长连接。");
    }

    default String streamMode(ChannelDefinition channel) {
        return metadataValue(channel, "connectionMode", "connectionModeEnv", "http");
    }

    default ChannelInboundMessage genericInbound(String channelId, Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        return new ChannelInboundMessage(
                firstNonBlank(channelId, stringValue(safePayload.get("channelId")), "api"),
                firstNonBlank(stringValue(safePayload.get("externalConversationId")), stringValue(safePayload.get("conversationId")), "default"),
                firstNonBlank(stringValue(safePayload.get("externalUserId")), stringValue(safePayload.get("userId")), "external"),
                firstNonBlank(stringValue(safePayload.get("messageType")), stringValue(safePayload.get("msgtype")), "text"),
                stringValue(safePayload.get("text")),
                ChannelMaps.stringMap(safePayload.get("metadata")),
                safePayload);
    }

    default String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
        return metadataValue(channel, directKey, envKey, "");
    }

    default String metadataValue(ChannelDefinition channel, String directKey, String envKey, String fallback) {
        if (channel == null || channel.metadata() == null) {
            return fallback;
        }
        String direct = stringValue(channel.metadata().get(directKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = stringValue(channel.metadata().get(envKey));
        String envValue = envName.isBlank() ? "" : stringValue(System.getenv(envName));
        return envValue.isBlank() ? fallback : envValue;
    }

    default String safeType(ChannelDefinition channel) {
        return channel == null || channel.type() == null ? "" : channel.type().trim().toLowerCase();
    }

    default String firstNonBlank(String... values) {
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

    default String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
