package com.github.clawagent.channel.dingtalk;

import com.github.clawagent.channel.ChannelMediaSupport;
import com.github.clawagent.channel.ChannelEventMetadataSupport;
import com.github.clawagent.channel.ChannelRichRenderSupport;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉 HTTP 入站 payload 解析和自定义机器人签名校验。
 */
public final class DingtalkInboundAdapter {
    private static final String RAW_HEADERS = "_headers";
    private static final String RAW_QUERY = "_query";

    private DingtalkInboundAdapter() {
    }

    public static ChannelInboundMessage adapt(ChannelDefinition channel, String channelId, Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        verifySignature(channel, safePayload);
        Map<String, Object> textNode = mapValue(safePayload.get("text"));
        boolean hasMessagePayload = hasKnownMessageNode(safePayload);
        String eventType = firstNonBlank(stringValue(safePayload.get("eventType")), stringValue(safePayload.get("type")), "");
        String messageType = firstNonBlank(stringValue(safePayload.get("msgtype")), stringValue(safePayload.get("messageType")), eventType, "text");
        String text = firstNonBlank(
                stringValue(textNode.get("content")),
                stringValue(safePayload.get("text")),
                stringValue(safePayload.get("content")));
        if (text.isBlank()) {
            text = placeholderText(messageType, safePayload);
        }

        Map<String, String> metadata = stringMap(safePayload.get("metadata"));
        String messageId = firstNonBlank(stringValue(safePayload.get("msgId")), stringValue(safePayload.get("messageId")), stringValue(safePayload.get("eventId")), "");
        String conversationId = firstNonBlank(stringValue(safePayload.get("conversationId")), stringValue(safePayload.get("openConversationId")), stringValue(safePayload.get("chatId")), "default");
        String externalUserId = firstNonBlank(new String[] {
                stringValue(safePayload.get("senderStaffId")),
                stringValue(safePayload.get("senderId")),
                stringValue(safePayload.get("operatorStaffId")),
                stringValue(safePayload.get("userId"))
        }, "external");
        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.ofEntries(
                Map.entry(ChannelEventMetadataSupport.ADAPTER, "dingtalk"),
                Map.entry(ChannelEventMetadataSupport.EVENT_SOURCE, "http"),
                Map.entry(ChannelEventMetadataSupport.EVENT_TYPE, firstNonBlank(eventType, stringValue(safePayload.get("msgtype")), "")),
                Map.entry(ChannelEventMetadataSupport.EVENT_ID, firstNonBlank(stringValue(safePayload.get("eventId")), messageId, "")),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_ID, messageId),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_CREATE_TIME, firstNonBlank(stringValue(safePayload.get("createAt")), stringValue(safePayload.get("createTime")), stringValue(safePayload.get("msgCreateTime")), "")),
                Map.entry(ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, hasMessagePayload ? messageType : ""),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_ID, conversationId),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_TYPE, stringValue(safePayload.get("conversationType"))),
                Map.entry(ChannelEventMetadataSupport.EXTERNAL_USER_ID, externalUserId),
                Map.entry(ChannelEventMetadataSupport.SENDER_NAME, stringValue(safePayload.get("senderNick"))),
                Map.entry(ChannelEventMetadataSupport.CORP_ID, stringValue(safePayload.get("corpId")))
        ));
        metadata.put("channel.adapter", "dingtalk");
        metadata.put("channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.messageId", messageId);
        putIfPresent(metadata, "channel.eventId", firstNonBlank(stringValue(safePayload.get("eventId")), stringValue(safePayload.get("msgId")), stringValue(safePayload.get("messageId")), ""));
        putIfPresent(metadata, "channel.eventType", firstNonBlank(eventType, stringValue(safePayload.get("msgtype")), ""));
        putIfPresent(metadata, "channel.messageCreateTime", firstNonBlank(stringValue(safePayload.get("createAt")), stringValue(safePayload.get("createTime")), stringValue(safePayload.get("msgCreateTime")), ""));
        putIfPresent(metadata, "channel.conversationType", stringValue(safePayload.get("conversationType")));
        putIfPresent(metadata, "channel.webhookConversationType", stringValue(safePayload.get("conversationType")));
        putIfPresent(metadata, "channel.senderNick", stringValue(safePayload.get("senderNick")));
        putIfPresent(metadata, "channel.dingtalk.picUrl", stringValue(mapValue(safePayload.get("image")).get("picUrl")));
        putIfPresent(metadata, "channel.dingtalk.fileName", firstNonBlank(stringValue(mapValue(safePayload.get("file")).get("fileName")), stringValue(safePayload.get("fileName")), ""));
        putIfPresent(metadata, "channel.dingtalk.downloadUrl", firstNonBlank(stringValue(mapValue(safePayload.get("file")).get("downloadUrl")), stringValue(safePayload.get("downloadUrl")), ""));
        putIfPresent(metadata, "channel.dingtalk.downloadCode", firstNonBlank(stringValue(mapValue(safePayload.get("file")).get("downloadCode")), stringValue(safePayload.get("downloadCode")), ""));
        putIfPresent(metadata, "channel.dingtalk.markdownTitle", stringValue(mapValue(safePayload.get("markdown")).get("title")));
        putIfPresent(metadata, "channel.dingtalk.markdownText", stringValue(mapValue(safePayload.get("markdown")).get("text")));
        putIfPresent(metadata, "channel.dingtalk.cardTitle", firstNonBlank(stringValue(mapValue(safePayload.get("actionCard")).get("title")), stringValue(mapValue(safePayload.get("card")).get("title")), ""));
        putAttachmentMetadata(metadata, channel, channelId, safePayload, messageType);

        return new ChannelInboundMessage(
                channelId,
                conversationId,
                externalUserId,
                messageType,
                text,
                metadata,
                safePayload);
    }

    public static Map<String, String> signedQuery(String secret, long timestampMillis) {
        if (secret == null || secret.isBlank()) {
            return Map.of();
        }
        try {
            String stringToSign = timestampMillis + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            return Map.of(
                    "timestamp", String.valueOf(timestampMillis),
                    "sign", URLEncoder.encode(sign, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("生成钉钉机器人签名失败", e);
        }
    }

    public static boolean detect(Map<String, Object> payload) {
        if (payload == null) {
            return false;
        }
        String eventType = firstNonBlank(stringValue(payload.get("eventType")), stringValue(payload.get("type")), "");
        // 非消息事件没有 msgtype 内容节点，也要保留到钉钉 adapter 里做统一审计映射。
        return (hasKnownMessageNode(payload) || !eventType.isBlank())
                && (!stringValue(payload.get("conversationId")).isBlank()
                || !stringValue(payload.get("openConversationId")).isBlank()
                || !stringValue(payload.get("senderStaffId")).isBlank()
                || !stringValue(payload.get("senderId")).isBlank()
                || !stringValue(payload.get("operatorStaffId")).isBlank()
                || !stringValue(payload.get("eventId")).isBlank());
    }

    private static boolean hasKnownMessageNode(Map<String, Object> payload) {
        return !mapValue(payload.get("text")).isEmpty()
                || !mapValue(payload.get("image")).isEmpty()
                || !mapValue(payload.get("file")).isEmpty()
                || !mapValue(payload.get("markdown")).isEmpty()
                || !mapValue(payload.get("actionCard")).isEmpty()
                || !mapValue(payload.get("card")).isEmpty()
                || !stringValue(payload.get("content")).isBlank();
    }

    private static String placeholderText(String messageType, Map<String, Object> payload) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        return switch (normalized) {
            case "image", "picture" -> "[钉钉图片] " + firstNonBlank(
                    stringValue(mapValue(payload.get("image")).get("picUrl")),
                    stringValue(payload.get("picUrl")),
                    "未提供图片地址");
            case "file" -> "[钉钉文件] " + firstNonBlank(
                    stringValue(mapValue(payload.get("file")).get("fileName")),
                    stringValue(payload.get("fileName")),
                    stringValue(mapValue(payload.get("file")).get("downloadUrl")),
                    "未提供文件名");
            case "markdown" -> "[钉钉 Markdown] " + firstNonBlank(
                    stringValue(mapValue(payload.get("markdown")).get("title")),
                    stringValue(mapValue(payload.get("markdown")).get("text")),
                    "请在平台查看完整内容");
            case "actioncard", "feedcard", "card" -> "[钉钉卡片] " + firstNonBlank(
                    stringValue(mapValue(payload.get("actionCard")).get("title")),
                    stringValue(mapValue(payload.get("card")).get("title")),
                    stringValue(payload.get("title")),
                    "请在平台查看完整内容");
            default -> "[钉钉非文本消息] type=" + firstNonBlank(messageType, "", "unknown");
        };
    }

    private static void verifySignature(ChannelDefinition channel, Map<String, Object> payload) {
        String secret = metadataValue(channel, "secret", "secretEnv");
        if (secret.isBlank()) {
            return;
        }
        Map<String, Object> query = mapValue(payload.get(RAW_QUERY));
        Map<String, Object> headers = mapValue(payload.get(RAW_HEADERS));
        String timestamp = firstNonBlank(
                stringValue(query.get("timestamp")),
                stringValue(payload.get("timestamp")),
                stringValue(headers.get("x-dingtalk-timestamp")),
                "");
        String actualSign = firstNonBlank(
                stringValue(query.get("sign")),
                stringValue(payload.get("sign")),
                stringValue(headers.get("x-dingtalk-sign")),
                "");
        if (timestamp.isBlank() || actualSign.isBlank()) {
            throw new IllegalArgumentException("钉钉回调已配置加签 Secret，但请求缺少 timestamp 或 sign");
        }
        String expectedSign = expectedSign(secret, timestamp);
        // Spring RequestParam 通常已 URL decode；外部网关也可能把原始 query 原样透传，因此两侧统一 decode 后比较。
        if (!constantTimeEquals(urlDecode(expectedSign), urlDecode(actualSign))) {
            throw new IllegalArgumentException("钉钉回调签名校验失败");
        }
    }

    private static String expectedSign(String secret, String timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成钉钉回调签名失败", e);
        }
    }

    private static String urlDecode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
        if (channel == null || channel.metadata() == null) {
            return "";
        }
        String direct = stringValue(channel.metadata().get(directKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = stringValue(channel.metadata().get(envKey));
        return envName.isBlank() ? "" : stringValue(System.getenv(envName));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static void putAttachmentMetadata(Map<String, String> metadata, ChannelDefinition channel,
                                              String channelId, Map<String, Object> payload, String messageType) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        List<Map<String, String>> attachments = new ArrayList<>();
        String messageId = firstNonBlank(stringValue(payload.get("msgId")), stringValue(payload.get("messageId")), stringValue(payload.get("eventId")), "message");
        if ("image".equals(normalized) || "picture".equals(normalized)) {
            String picUrl = firstNonBlank(stringValue(mapValue(payload.get("image")).get("picUrl")), stringValue(payload.get("picUrl")), "");
            if (!picUrl.isBlank()) {
                attachments.add(ChannelMediaSupport.download(channel, "dingtalk", "image", picUrl,
                        Map.of(), channelId, messageId, picUrl, "dingtalk-image", 30000));
            }
        } else if ("file".equals(normalized)) {
            Map<String, Object> file = mapValue(payload.get("file"));
            String fileName = firstNonBlank(stringValue(file.get("fileName")), stringValue(payload.get("fileName")), "dingtalk-file");
            String downloadUrl = firstNonBlank(stringValue(file.get("downloadUrl")), stringValue(payload.get("downloadUrl")), "");
            String downloadCode = firstNonBlank(stringValue(file.get("downloadCode")), stringValue(payload.get("downloadCode")), "");
            Map<String, String> values = new LinkedHashMap<>();
            values.put("fileName", fileName);
            values.put("downloadCode", downloadCode);
            if (downloadUrl.isBlank()) {
                attachments.add(DingtalkMediaDownloader.downloadCode(channel, "file", downloadCode, fileName, channelId, messageId));
            } else {
                Map<String, String> attachment = ChannelMediaSupport.download(channel, "dingtalk", "file", downloadUrl,
                        Map.of(), channelId, messageId, downloadCode, fileName, 30000);
                attachment.putAll(values);
                attachments.add(attachment);
            }
        } else if ("markdown".equals(normalized)) {
            Map<String, Object> markdown = mapValue(payload.get("markdown"));
            attachments.add(ChannelRichRenderSupport.richAttachment(
                    "dingtalk", "markdown", "markdown", stringValue(markdown.get("title")), markdown));
        } else if ("actioncard".equals(normalized) || "feedcard".equals(normalized) || "card".equals(normalized)) {
            Map<String, Object> actionCard = mapValue(payload.get("actionCard"));
            Map<String, Object> card = mapValue(payload.get("card"));
            Map<String, Object> rich = new LinkedHashMap<>();
            rich.putAll(card);
            rich.putAll(actionCard);
            if (rich.isEmpty()) {
                rich.putAll(payload);
            }
            attachments.add(ChannelRichRenderSupport.richAttachment("dingtalk", "card", normalized,
                    firstNonBlank(stringValue(actionCard.get("title")), stringValue(card.get("title")), stringValue(payload.get("title")), ""),
                    rich));
        }
        ChannelMediaSupport.putAttachmentsMetadata(metadata, attachments);
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        return firstNonBlank(new String[] { first, second }, fallback);
    }

    private static String firstNonBlank(String first, String second, String third, String fallback) {
        return firstNonBlank(new String[] { first, second, third }, fallback);
    }

    private static String firstNonBlank(String[] values, String fallback) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
