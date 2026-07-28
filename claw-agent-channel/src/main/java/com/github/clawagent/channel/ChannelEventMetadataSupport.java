package com.github.clawagent.channel;

import java.util.Map;

/**
 * ChannelEventMetadataSupport 统一补齐跨平台事件字段。
 * 各平台保留自己的 metadata，审计、检索和权限策略优先读取这些标准 channel.* 字段。
 */
public final class ChannelEventMetadataSupport {
    public static final String ADAPTER = "adapter";
    public static final String EVENT_SOURCE = "eventSource";
    public static final String EVENT_CATEGORY = "eventCategory";
    public static final String EVENT_TYPE = "eventType";
    public static final String PLATFORM_EVENT_TYPE = "platformEventType";
    public static final String EVENT_SEMANTIC = "eventSemantic";
    public static final String EVENT_ACTION = "eventAction";
    public static final String EVENT_PROVIDER = "eventProvider";
    public static final String EVENT_ID = "eventId";
    public static final String EVENT_CREATE_TIME = "eventCreateTime";
    public static final String MESSAGE_ID = "messageId";
    public static final String MESSAGE_CREATE_TIME = "messageCreateTime";
    public static final String PLATFORM_MESSAGE_TYPE = "platformMessageType";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String CONVERSATION_TYPE = "conversationType";
    public static final String EXTERNAL_USER_ID = "externalUserId";
    public static final String SENDER_NAME = "senderName";
    public static final String TENANT_KEY = "tenantKey";
    public static final String APP_ID = "appId";
    public static final String CORP_ID = "corpId";

    private ChannelEventMetadataSupport() {
    }

    public static void putStandardEvent(Map<String, String> metadata, Map<String, String> values) {
        if (metadata == null || values == null || values.isEmpty()) {
            return;
        }
        put(metadata, "channel.adapter", values.get(ADAPTER));
        put(metadata, "channel.eventSource", values.get(EVENT_SOURCE));
        EventSemantic semantic = inferSemantic(values);
        put(metadata, "channel.eventCategory", firstNonBlank(values.get(EVENT_CATEGORY), semantic.category()));
        put(metadata, "channel.eventType", values.get(EVENT_TYPE));
        put(metadata, "channel.platformEventType", firstNonBlank(values.get(PLATFORM_EVENT_TYPE), values.get(EVENT_TYPE)));
        put(metadata, "channel.eventSemantic", firstNonBlank(values.get(EVENT_SEMANTIC), semantic.semantic()));
        put(metadata, "channel.eventAction", firstNonBlank(values.get(EVENT_ACTION), semantic.action()));
        put(metadata, "channel.eventProvider", firstNonBlank(values.get(EVENT_PROVIDER), values.get(ADAPTER)));
        put(metadata, "channel.eventId", values.get(EVENT_ID));
        put(metadata, "channel.eventCreateTime", values.get(EVENT_CREATE_TIME));
        put(metadata, "channel.messageId", values.get(MESSAGE_ID));
        put(metadata, "channel.messageCreateTime", values.get(MESSAGE_CREATE_TIME));
        put(metadata, "channel.platformMessageType", values.get(PLATFORM_MESSAGE_TYPE));
        put(metadata, "channel.conversationId", values.get(CONVERSATION_ID));
        put(metadata, "channel.conversationType", values.get(CONVERSATION_TYPE));
        put(metadata, "channel.externalUserId", values.get(EXTERNAL_USER_ID));
        put(metadata, "channel.senderName", values.get(SENDER_NAME));
        put(metadata, "channel.tenantKey", values.get(TENANT_KEY));
        put(metadata, "channel.appId", values.get(APP_ID));
        put(metadata, "channel.corpId", values.get(CORP_ID));
    }

    private static EventSemantic inferSemantic(Map<String, String> values) {
        String messageType = values.get(PLATFORM_MESSAGE_TYPE);
        if (messageType != null && !messageType.isBlank()) {
            return new EventSemantic("message", "received", "message.received");
        }
        String eventType = firstNonBlank(values.get(EVENT_TYPE), values.get(PLATFORM_EVENT_TYPE)).toLowerCase();
        if (eventType.isBlank()) {
            return new EventSemantic("", "", "");
        }
        if (eventType.contains("reaction") && containsAny(eventType, "delete", "deleted", "remove", "removed")) {
            return new EventSemantic("reaction", "deleted", "reaction.deleted");
        }
        if (eventType.contains("reaction")) {
            return new EventSemantic("reaction", "created", "reaction.created");
        }
        if (eventType.contains("read")) {
            return new EventSemantic("message", "read", "message.read");
        }
        if (containsAny(eventType, "member", "user", "bot") && containsAny(eventType, "add", "added", "join", "joined")) {
            return new EventSemantic("member", "added", "member.added");
        }
        if (containsAny(eventType, "member", "user", "bot") && containsAny(eventType, "delete", "deleted", "remove", "removed", "leave", "left")) {
            return new EventSemantic("member", "removed", "member.removed");
        }
        if (eventType.contains("message") && containsAny(eventType, "delete", "deleted", "retract", "recall")) {
            return new EventSemantic("message", "deleted", "message.deleted");
        }
        if (eventType.contains("message") || eventType.contains("chatbot") || eventType.contains("chat")) {
            return new EventSemantic("message", "received", "message.received");
        }
        if (containsAny(eventType, "card", "interactive", "menu")) {
            return new EventSemantic("interaction", "triggered", "interaction.triggered");
        }
        return new EventSemantic("event", "received", "event.received");
    }

    private static boolean containsAny(String value, String... fragments) {
        if (value == null || fragments == null) {
            return false;
        }
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank() && value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void put(Map<String, String> metadata, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
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

    private record EventSemantic(String category, String action, String semantic) {
    }
}
