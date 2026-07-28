package com.github.clawagent.channel.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelEventMetadataSupport;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.channel.ChannelMediaSupport;
import com.github.clawagent.channel.ChannelRichRenderSupport;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.http.AgentHttpClient;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书/Lark HTTP 入站 payload 解析、解密和 Verification Token 校验。
 */
public final class FeishuInboundAdapter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String FEISHU_TENANT_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String FEISHU_IMAGE_URL = "https://open.feishu.cn/open-apis/im/v1/images/%s?type=message";
    private static final String FEISHU_FILE_URL = "https://open.feishu.cn/open-apis/im/v1/messages/%s/resources/%s?type=file";
    private static final String RAW_HEADERS = "_headers";
    private static final String RAW_QUERY = "_query";

    private FeishuInboundAdapter() {
    }

    public static ChannelInboundPayloadResult adapt(ChannelDefinition channel, String channelId, Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = normalizeEncryptedPayload(channel, payload == null ? Map.of() : payload);
        verifyToken(channel, normalizedPayload);
        String challenge = stringValue(normalizedPayload.get("challenge"));
        if ("url_verification".equals(stringValue(normalizedPayload.get("type"))) || !challenge.isBlank()) {
            return ChannelInboundPayloadResult.immediate(Map.of("challenge", challenge));
        }

        Map<String, Object> event = mapValue(normalizedPayload.get("event"));
        Map<String, Object> message = mapValue(event.get("message"));
        Map<String, Object> header = mapValue(normalizedPayload.get("header"));
        Map<String, Object> sender = mapValue(event.get("sender"));
        Map<String, Object> senderId = mapValue(sender.get("sender_id"));
        String eventType = stringValue(header.get("event_type"));
        boolean hasMessagePayload = !message.isEmpty();
        String messageType = firstNonBlank(new String[] {
                stringValue(message.get("message_type")),
                stringValue(message.get("msg_type")),
                stringValue(event.get("message_type")),
                eventType
        }, "event");
        String content = stringValue(message.get("content"));
        Map<String, Object> contentMap = parseJsonContent(content);
        String text = firstNonBlank(extractTextFromJsonContent(contentMap), stringValue(normalizedPayload.get("text")), "");
        if (text.isBlank() && "text".equalsIgnoreCase(messageType)) {
            text = content;
        }
        if (text.isBlank()) {
            text = placeholderText(messageType, contentMap);
        }

        Map<String, String> metadata = stringMap(normalizedPayload.get("metadata"));
        String messageId = firstNonBlank(new String[] {
                stringValue(message.get("message_id")),
                stringValue(event.get("message_id")),
                stringValue(event.get("event_id")),
                stringValue(header.get("event_id"))
        }, "");
        String conversationId = firstNonBlank(
                stringValue(message.get("chat_id")),
                stringValue(event.get("chat_id")),
                stringValue(mapValue(event.get("chat")).get("chat_id")),
                "default");
        String externalUserId = firstNonBlank(new String[] {
                stringValue(senderId.get("user_id")),
                stringValue(senderId.get("open_id")),
                stringValue(event.get("open_id")),
                stringValue(event.get("operator_id"))
        }, "external");
        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.ofEntries(
                Map.entry(ChannelEventMetadataSupport.ADAPTER, "feishu"),
                Map.entry(ChannelEventMetadataSupport.EVENT_SOURCE, "http"),
                Map.entry(ChannelEventMetadataSupport.EVENT_TYPE, eventType),
                Map.entry(ChannelEventMetadataSupport.EVENT_ID, firstNonBlank(stringValue(header.get("event_id")), stringValue(event.get("event_id")), "")),
                Map.entry(ChannelEventMetadataSupport.EVENT_CREATE_TIME, firstNonBlank(stringValue(header.get("create_time")), stringValue(event.get("create_time")), "")),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_ID, messageId),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_CREATE_TIME, stringValue(message.get("create_time"))),
                Map.entry(ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, hasMessagePayload ? messageType : ""),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_ID, conversationId),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_TYPE, firstNonBlank(stringValue(message.get("chat_type")), stringValue(event.get("chat_type")), "")),
                Map.entry(ChannelEventMetadataSupport.EXTERNAL_USER_ID, externalUserId),
                Map.entry(ChannelEventMetadataSupport.TENANT_KEY, firstNonBlank(stringValue(senderId.get("tenant_key")), stringValue(event.get("tenant_key")), ""))
        ));
        metadata.put("channel.adapter", "feishu");
        metadata.put("channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.messageId", messageId);
        putIfPresent(metadata, "channel.eventType", eventType);
        putIfPresent(metadata, "channel.eventId", firstNonBlank(stringValue(header.get("event_id")), stringValue(event.get("event_id")), ""));
        putIfPresent(metadata, "channel.eventCreateTime", firstNonBlank(stringValue(header.get("create_time")), stringValue(event.get("create_time")), ""));
        putIfPresent(metadata, "channel.messageCreateTime", stringValue(message.get("create_time")));
        putIfPresent(metadata, "channel.conversationType", firstNonBlank(stringValue(message.get("chat_type")), stringValue(event.get("chat_type")), ""));
        putIfPresent(metadata, "channel.senderTenantKey", firstNonBlank(stringValue(senderId.get("tenant_key")), stringValue(event.get("tenant_key")), ""));
        putIfPresent(metadata, "channel.feishu.imageKey", stringValue(contentMap.get("image_key")));
        putIfPresent(metadata, "channel.feishu.fileKey", firstNonBlank(stringValue(contentMap.get("file_key")), stringValue(contentMap.get("fileKey")), ""));
        putIfPresent(metadata, "channel.feishu.fileName", firstNonBlank(stringValue(contentMap.get("file_name")), stringValue(contentMap.get("fileName")), ""));
        putIfPresent(metadata, "channel.feishu.cardTitle", firstNonBlank(stringValue(contentMap.get("title")), stringValue(contentMap.get("header")), ""));
        putAttachmentMetadata(metadata, channel, channelId, messageId, messageType, contentMap);

        return ChannelInboundPayloadResult.message(new ChannelInboundMessage(
                channelId,
                conversationId,
                externalUserId,
                messageType,
                text,
                metadata,
                normalizedPayload));
    }

    public static boolean supports(String channelType) {
        String normalized = channelType == null ? "" : channelType.trim().toLowerCase();
        return "feishu".equals(normalized) || "lark".equals(normalized);
    }

    public static boolean detect(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        Map<String, Object> event = mapValue(payload.get("event"));
        Map<String, Object> message = mapValue(event.get("message"));
        Map<String, Object> header = mapValue(payload.get("header"));
        // 自动识别看平台事件骨架；消息、已读、成员、表情等非消息事件都应进入飞书 adapter。
        return (!message.isEmpty() || !event.isEmpty())
                && (!header.isEmpty() || !mapValue(event.get("sender")).isEmpty())
                && (!stringValue(header.get("event_type")).isBlank()
                || !stringValue(event.get("type")).isBlank()
                || !stringValue(event.get("event_type")).isBlank());
    }

    private static Map<String, Object> normalizeEncryptedPayload(ChannelDefinition channel, Map<String, Object> payload) {
        String encrypt = stringValue(payload.get("encrypt"));
        if (encrypt.isBlank()) {
            return payload;
        }
        String encryptKey = metadataValue(channel, "encryptKey", "encryptKeyEnv");
        if (encryptKey.isBlank()) {
            throw new IllegalStateException("飞书回调已加密，但 Channel metadata 未配置 encryptKey 或 encryptKeyEnv");
        }
        try {
            Map<String, Object> decrypted = OBJECT_MAPPER.readValue(decryptPayload(encrypt, encryptKey), MAP_TYPE);
            Map<String, Object> result = new LinkedHashMap<>(decrypted);
            // 保留原始 headers/query，后续审计和平台差异处理仍可读取。
            copyIfPresent(payload, result, RAW_HEADERS);
            copyIfPresent(payload, result, RAW_QUERY);
            result.put("rawEncryptedPayload", payload);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("解密飞书回调 payload 失败", e);
        }
    }

    private static String decryptPayload(String encryptedPayload, String encryptKey) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedPayload);
        if (encryptedBytes.length <= 16) {
            throw new IllegalArgumentException("飞书加密 payload 长度不合法");
        }
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        byte[] cipherBytes = new byte[encryptedBytes.length - 16];
        System.arraycopy(encryptedBytes, 0, iv, 0, iv.length);
        System.arraycopy(encryptedBytes, 16, cipherBytes, 0, cipherBytes.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] padded = cipher.doFinal(cipherBytes);
        return new String(stripPkcs7Padding(padded), StandardCharsets.UTF_8);
    }

    private static byte[] stripPkcs7Padding(byte[] padded) {
        if (padded.length == 0) {
            return padded;
        }
        int padding = padded[padded.length - 1] & 0xff;
        if (padding <= 0 || padding > 16 || padding > padded.length) {
            throw new IllegalArgumentException("PKCS7Padding 不合法");
        }
        byte[] result = new byte[padded.length - padding];
        System.arraycopy(padded, 0, result, 0, result.length);
        return result;
    }

    private static void verifyToken(ChannelDefinition channel, Map<String, Object> payload) {
        String expected = metadataValue(channel, "verificationToken", "verificationTokenEnv");
        if (expected.isBlank()) {
            return;
        }
        String actual = firstNonBlank(
                stringValue(payload.get("token")),
                stringValue(mapValue(payload.get("header")).get("token")),
                "");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("飞书回调 Verification Token 校验失败");
        }
    }

    private static Map<String, Object> parseJsonContent(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(content, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String extractTextFromJsonContent(Map<String, Object> content) {
        return firstNonBlank(
                stringValue(content.get("text")),
                stringValue(content.get("content")),
                stringValue(content.get("title")),
                "");
    }

    private static String placeholderText(String messageType, Map<String, Object> content) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        return switch (normalized) {
            case "image", "img" -> "[飞书图片] " + firstNonBlank(stringValue(content.get("image_key")), "", "未提供 image_key");
            case "file" -> "[飞书文件] " + firstNonBlank(
                    stringValue(content.get("file_name")),
                    stringValue(content.get("fileName")),
                    stringValue(content.get("file_key")),
                    "未提供文件名");
            case "audio", "media" -> "[飞书音视频] " + firstNonBlank(stringValue(content.get("file_key")), stringValue(content.get("fileKey")), "未提供 file_key");
            case "interactive", "card", "post", "rich_text" -> "[飞书卡片/富文本] " + firstNonBlank(
                    richTitle(content),
                    truncate(extractRichPlainText(content), 200),
                    "请在平台查看完整内容");
            default -> "[飞书非文本消息] type=" + firstNonBlank(messageType, "", "unknown");
        };
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

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
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

    static void putAttachmentMetadata(Map<String, String> metadata, ChannelDefinition channel, String channelId,
                                      String messageId, String messageType, Map<String, Object> contentMap) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        List<Map<String, String>> attachments = new ArrayList<>();
        if ("image".equals(normalized)) {
            String imageKey = stringValue(contentMap.get("image_key"));
            if (!imageKey.isBlank()) {
                attachments.add(downloadImage(channel, channelId, messageId, imageKey));
                ChannelMediaSupport.putAttachmentsMetadata(metadata, attachments);
            }
            return;
        }
        if ("file".equals(normalized) || "audio".equals(normalized) || "media".equals(normalized)) {
            String fileKey = firstNonBlank(stringValue(contentMap.get("file_key")), stringValue(contentMap.get("fileKey")), "");
            if (!fileKey.isBlank()) {
                Map<String, String> attachment = new LinkedHashMap<>();
                attachment.put("fileKey", fileKey);
                String fileName = firstNonBlank(stringValue(contentMap.get("file_name")), stringValue(contentMap.get("fileName")), "");
                if (!fileName.isBlank()) {
                    attachment.put("fileName", fileName);
                }
                // 飞书音频和视频同样走 message resource 下载接口，保留原始类型便于后续审计过滤。
                attachments.add(downloadFile(channel, channelId, messageId, normalized, fileKey, fileName, attachment));
                ChannelMediaSupport.putAttachmentsMetadata(metadata, attachments);
            }
        } else if (isRichMessage(normalized)) {
            attachments.add(richAttachment(normalized, contentMap));
            ChannelMediaSupport.putAttachmentsMetadata(metadata, attachments);
        }
    }

    private static boolean isRichMessage(String normalizedMessageType) {
        return "interactive".equals(normalizedMessageType)
                || "card".equals(normalizedMessageType)
                || "post".equals(normalizedMessageType)
                || "rich_text".equals(normalizedMessageType);
    }

    private static Map<String, String> richAttachment(String messageType, Map<String, Object> contentMap) {
        return ChannelRichRenderSupport.richAttachment("feishu", "rich", messageType, richTitle(contentMap), contentMap);
    }

    private static String richTitle(Map<String, Object> contentMap) {
        Map<String, Object> header = mapValue(contentMap.get("header"));
        Map<String, Object> title = mapValue(contentMap.get("title"));
        Map<String, Object> headerTitle = mapValue(header.get("title"));
        return firstNonBlank(new String[] {
                stringValue(title.get("content")),
                stringValue(contentMap.get("title")),
                stringValue(headerTitle.get("content")),
                stringValue(header.get("title"))
        }, "");
    }

    private static String extractRichPlainText(Object value) {
        StringBuilder builder = new StringBuilder();
        appendRichPlainText(value, builder, 0);
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private static void appendRichPlainText(Object value, StringBuilder builder, int depth) {
        if (value == null || builder.length() >= 1000 || depth > 8) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            // 优先抽取富文本常见字段，避免把平台内部 id、样式等噪声塞进模型上下文。
            for (String key : List.of("text", "title", "content", "value", "elements")) {
                if (map.containsKey(key)) {
                    appendRichPlainText(map.get(key), builder, depth + 1);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                appendRichPlainText(item, builder, depth + 1);
            }
            return;
        }
        String text = stringValue(value);
        if (!text.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(text);
        }
    }

    private static String compactJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return stringValue(value);
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private static Map<String, String> downloadImage(ChannelDefinition channel, String channelId, String messageId, String imageKey) {
        String token = tenantAccessToken(channel);
        if (token.isBlank()) {
            return ChannelMediaSupport.attachment("feishu", "image", Map.of(
                    "imageKey", imageKey,
                    "downloadStatus", "skipped",
                    "downloadReason", "missing-token"));
        }
        String url = String.format(FEISHU_IMAGE_URL, urlEncode(imageKey));
        Map<String, String> result = ChannelMediaSupport.download(channel, "feishu", "image", url,
                Map.of("Authorization", "Bearer " + token), channelId, messageId, imageKey, imageKey, 30000);
        result.put("imageKey", imageKey);
        return result;
    }

    private static Map<String, String> downloadFile(ChannelDefinition channel, String channelId, String messageId,
                                                   String attachmentType, String fileKey, String fileName,
                                                   Map<String, String> fallback) {
        String type = firstNonBlank(attachmentType, "file", "file");
        if (messageId == null || messageId.isBlank()) {
            Map<String, String> attachment = ChannelMediaSupport.attachment("feishu", type, fallback);
            attachment.put("downloadStatus", "skipped");
            attachment.put("downloadReason", "missing-message-id");
            return attachment;
        }
        String token = tenantAccessToken(channel);
        if (token.isBlank()) {
            Map<String, String> attachment = ChannelMediaSupport.attachment("feishu", type, fallback);
            attachment.put("downloadStatus", "skipped");
            attachment.put("downloadReason", "missing-token");
            return attachment;
        }
        String url = String.format(FEISHU_FILE_URL, urlEncode(messageId), urlEncode(fileKey));
        Map<String, String> result = ChannelMediaSupport.download(channel, "feishu", type, url,
                Map.of("Authorization", "Bearer " + token), channelId, messageId, fileKey, fileName, 30000);
        result.putAll(fallback);
        return result;
    }

    private static String tenantAccessToken(ChannelDefinition channel) {
        String token = metadataValue(channel, "tenantAccessToken", "tenantAccessTokenEnv");
        if (!token.isBlank()) {
            return token;
        }
        String appId = metadataValue(channel, "appId", "appIdEnv");
        String appSecret = metadataValue(channel, "appSecret", "appSecretEnv");
        if (appId.isBlank() || appSecret.isBlank()) {
            return "";
        }
        try {
            String body = OBJECT_MAPPER.writeValueAsString(Map.of("app_id", appId, "app_secret", appSecret));
            AgentHttpClient.AgentHttpResponse response = AgentHttpClient.postJson(FEISHU_TENANT_TOKEN_URL, body, Map.of(), 30000);
            Map<String, Object> result = OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
            return stringValue(result.get("tenant_access_token"));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
