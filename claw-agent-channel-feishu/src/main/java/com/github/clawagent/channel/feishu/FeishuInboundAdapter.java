package com.github.clawagent.channel.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        Map<String, Object> sender = mapValue(event.get("sender"));
        Map<String, Object> senderId = mapValue(sender.get("sender_id"));
        String messageType = firstNonBlank(stringValue(message.get("message_type")), stringValue(message.get("msg_type")), "text");
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
        metadata.put("channel.adapter", "feishu");
        metadata.put("channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.messageId", stringValue(message.get("message_id")));
        putIfPresent(metadata, "channel.eventType", stringValue(mapValue(normalizedPayload.get("header")).get("event_type")));
        putIfPresent(metadata, "channel.feishu.imageKey", stringValue(contentMap.get("image_key")));
        putIfPresent(metadata, "channel.feishu.fileKey", firstNonBlank(stringValue(contentMap.get("file_key")), stringValue(contentMap.get("fileKey")), ""));
        putIfPresent(metadata, "channel.feishu.fileName", firstNonBlank(stringValue(contentMap.get("file_name")), stringValue(contentMap.get("fileName")), ""));
        putIfPresent(metadata, "channel.feishu.cardTitle", firstNonBlank(stringValue(contentMap.get("title")), stringValue(contentMap.get("header")), ""));
        putAttachmentMetadata(metadata, messageType, contentMap);

        return ChannelInboundPayloadResult.message(new ChannelInboundMessage(
                channelId,
                firstNonBlank(stringValue(message.get("chat_id")), stringValue(event.get("chat_id")), "default"),
                firstNonBlank(stringValue(senderId.get("user_id")), stringValue(senderId.get("open_id")), stringValue(event.get("open_id")), "external"),
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
        // 自动识别只看飞书事件骨架，真正的 token/encrypt 校验放在 adapt 阶段执行。
        return !message.isEmpty() && (!mapValue(payload.get("header")).isEmpty() || !mapValue(event.get("sender")).isEmpty());
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
                    stringValue(content.get("title")),
                    stringValue(content.get("header")),
                    stringValue(content.get("summary")),
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

    private static void putAttachmentMetadata(Map<String, String> metadata, String messageType, Map<String, Object> contentMap) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        if ("image".equals(normalized)) {
            String imageKey = stringValue(contentMap.get("image_key"));
            if (!imageKey.isBlank()) {
                metadata.put("attachments", attachmentJson("image", Map.of("imageKey", imageKey)));
            }
            return;
        }
        if ("file".equals(normalized)) {
            String fileKey = firstNonBlank(stringValue(contentMap.get("file_key")), stringValue(contentMap.get("fileKey")), "");
            if (!fileKey.isBlank()) {
                Map<String, String> attachment = new LinkedHashMap<>();
                attachment.put("fileKey", fileKey);
                String fileName = firstNonBlank(stringValue(contentMap.get("file_name")), stringValue(contentMap.get("fileName")), "");
                if (!fileName.isBlank()) {
                    attachment.put("fileName", fileName);
                }
                metadata.put("attachments", attachmentJson("file", attachment));
            }
        }
    }

    private static String attachmentJson(String type, Map<String, String> values) {
        Map<String, String> attachment = new LinkedHashMap<>();
        attachment.put("type", type);
        attachment.put("source", "feishu");
        attachment.putAll(values);
        try {
            return OBJECT_MAPPER.writeValueAsString(List.of(attachment));
        } catch (Exception ignored) {
            return "[]";
        }
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
