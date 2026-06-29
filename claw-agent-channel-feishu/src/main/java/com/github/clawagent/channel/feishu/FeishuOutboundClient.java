package com.github.clawagent.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelSendResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpRequest;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书/Lark 官方 HTTP 出站实现。
 */
public class FeishuOutboundClient {
    private static final String FEISHU_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String FEISHU_MESSAGE_URL = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";
    private static final String FEISHU_IMAGE_URL = "https://open.feishu.cn/open-apis/im/v1/images";
    private static final String FEISHU_FILE_URL = "https://open.feishu.cn/open-apis/im/v1/files";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, TokenCacheEntry> tenantTokens = new ConcurrentHashMap<>();

    public FeishuOutboundClient() {
    }

    public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        List<String> missingKeys = missingMetadata("appId/appIdEnv", metadataValue(channel, "appId", "appIdEnv"),
                "appSecret/appSecretEnv", metadataValue(channel, "appSecret", "appSecretEnv"));
        if (!missingKeys.isEmpty()) {
            return ChannelConnectivityStatus.incomplete(stringValue(channel.id()), safeType(channel), missingKeys,
                    "飞书出站回写需要 App ID 和 App Secret，建议通过环境变量配置。", Map.of("protocol", "feishu-http"));
        }
        try {
            String token = cachedTenantToken(channel);
            if (token.isBlank()) {
                return ChannelConnectivityStatus.failed(stringValue(channel.id()), safeType(channel),
                        "飞书 tenant_access_token 获取失败，请检查 App ID、App Secret 和应用权限。", Map.of("protocol", "feishu-http"));
            }
            return ChannelConnectivityStatus.ready(stringValue(channel.id()), safeType(channel), true,
                    "飞书 App 凭证可用，tenant_access_token 获取成功。", Map.of("protocol", "feishu-http"));
        } catch (Exception e) {
            return ChannelConnectivityStatus.failed(stringValue(channel.id()), safeType(channel),
                    safeError("飞书连通性检查失败", e), Map.of("protocol", "feishu-http"));
        }
    }

    public ChannelSendResult sendText(ChannelDefinition channel, String chatId, String text) {
        ChannelInboundMessage sourceMessage = new ChannelInboundMessage(
                channel == null ? "feishu" : channel.id(), chatId, "external", "text", text, Map.of(), Map.of());
        return sendMessage(channel, sourceMessage, text);
    }

    public ChannelSendResult sendMessage(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        String token = metadataValue(channel, "tenantAccessToken", "tenantAccessTokenEnv");
        if (token.isBlank()) {
            token = cachedTenantToken(channel);
        }
        String chatId = sourceMessage == null ? "" : stringValue(sourceMessage.externalConversationId());
        if (token.isBlank() || chatId.isBlank()) {
            return ChannelSendResult.failed("飞书出站需要 tenant_access_token 和 receive_id/chat_id。",
                    Map.of("protocol", "feishu-http", "reason", token.isBlank() ? "missing-token" : "missing-receive-id"));
        }
        try {
            Map<String, String> sourceMetadata = sourceMessage == null || sourceMessage.metadata() == null
                    ? Map.of()
                    : sourceMessage.metadata();
            String requestedType = normalizeMessageType(firstNonBlank(
                    metadataValue(sourceMetadata, "feishu.outboundMessageType", "feishu.outboundMessageTypeEnv"),
                    metadataValue(sourceMetadata, "outboundMessageType", "outboundMessageTypeEnv"),
                    metadataValue(channel, "outboundMessageType", "outboundMessageTypeEnv"),
                    "text"));
            List<FeishuMessagePayload> payloads = buildPayloads(channel, sourceMetadata, text, requestedType, token);
            if (payloads.isEmpty()) {
                return ChannelSendResult.failed("飞书出站没有可发送的消息内容。",
                        Map.of("protocol", "feishu-http", "messageType", requestedType, "reason", "empty-payload"));
            }
            List<String> responseBodies = new ArrayList<>();
            boolean allSuccess = true;
            for (FeishuMessagePayload payload : payloads) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("receive_id", chatId);
                body.put("msg_type", payload.messageType());
                body.put("content", payload.contentJson());
                AgentHttpResponse response = AgentHttpClient.execute(jsonPost(FEISHU_MESSAGE_URL, body)
                        .header("Authorization", "Bearer " + token));
                allSuccess = allSuccess && isSuccess(response);
                responseBodies.add(response.statusCode() + ":" + truncate(stringValue(response.body()), 500));
            }
            Map<String, String> details = new LinkedHashMap<>();
            details.put("protocol", "feishu-http");
            details.put("messageType", requestedType);
            details.put("messageCount", String.valueOf(payloads.size()));
            details.put("responseBody", truncate(String.join(" | ", responseBodies), 1200));
            return allSuccess
                    ? ChannelSendResult.sent("飞书消息已提交。", details)
                    : ChannelSendResult.failed("飞书平台返回失败。", details);
        } catch (Exception e) {
            throw new IllegalStateException("发送飞书出站消息失败", e);
        }
    }

    private List<FeishuMessagePayload> buildPayloads(
            ChannelDefinition channel,
            Map<String, String> sourceMetadata,
            String text,
            String requestedType,
            String token) throws Exception {
        List<FeishuMessagePayload> payloads = new ArrayList<>();
        List<OutboundAttachment> attachments = outboundAttachments(sourceMetadata);
        if ("auto".equals(requestedType)) {
            if (!attachments.isEmpty()) {
                requestedType = "attachments";
            } else if (hasAnyValue(sourceMetadata, "feishu.cardJson", "cardJson", "interactiveCardJson")) {
                requestedType = "interactive";
            } else if (looksLikeMarkdown(text)) {
                requestedType = "markdown";
            } else {
                requestedType = "text";
            }
        }
        switch (requestedType) {
            case "post", "rich_text" -> payloads.add(postPayload(text, false));
            case "markdown" -> payloads.add(postPayload(text, true));
            case "card", "interactive" -> payloads.add(interactivePayload(channel, sourceMetadata, text));
            case "image" -> payloads.add(imagePayload(channel, sourceMetadata, attachments, token));
            case "file" -> payloads.add(filePayload(channel, sourceMetadata, attachments, token));
            case "attachment", "attachments", "multimodal", "multi_modal" -> payloads.addAll(attachmentPayloads(channel, sourceMetadata, attachments, text, token));
            default -> payloads.add(textPayload(text));
        }
        return payloads.stream().filter(payload -> payload != null && !payload.contentJson().isBlank()).toList();
    }

    private FeishuMessagePayload textPayload(String text) throws Exception {
        return new FeishuMessagePayload("text", objectMapper.writeValueAsString(Map.of("text", firstNonBlank(text, " "))));
    }

    private FeishuMessagePayload postPayload(String text, boolean markdown) throws Exception {
        return new FeishuMessagePayload("post", objectMapper.writeValueAsString(postContent(firstNonBlank(text, " "), markdown)));
    }

    private FeishuMessagePayload interactivePayload(ChannelDefinition channel, Map<String, String> sourceMetadata, String text) throws Exception {
        String cardJson = firstNonBlank(
                metadataValue(sourceMetadata, "feishu.cardJson", "feishu.cardJsonEnv"),
                metadataValue(sourceMetadata, "cardJson", "cardJsonEnv"),
                metadataValue(sourceMetadata, "interactiveCardJson", "interactiveCardJsonEnv"),
                metadataValue(channel, "feishu.cardJson", "feishu.cardJsonEnv"),
                metadataValue(channel, "cardJson", "cardJsonEnv"));
        if (cardJson.isBlank()) {
            return new FeishuMessagePayload("interactive", objectMapper.writeValueAsString(defaultCard(text)));
        }
        JsonNode card = objectMapper.readTree(cardJson);
        return new FeishuMessagePayload("interactive", objectMapper.writeValueAsString(card));
    }

    private FeishuMessagePayload imagePayload(
            ChannelDefinition channel,
            Map<String, String> sourceMetadata,
            List<OutboundAttachment> attachments,
            String token) throws Exception {
        String imageKey = firstNonBlank(
                metadataValue(sourceMetadata, "feishu.imageKey", "feishu.imageKeyEnv"),
                metadataValue(sourceMetadata, "imageKey", "imageKeyEnv"),
                metadataValue(channel, "feishu.imageKey", "feishu.imageKeyEnv"));
        if (imageKey.isBlank()) {
            OutboundAttachment image = firstAttachment(attachments, "image");
            imageKey = firstNonBlank(image == null ? "" : image.platformKey(), image == null ? "" : image.extra("imageKey"));
            if (imageKey.isBlank() && image != null && !image.localPath().isBlank()) {
                imageKey = uploadImage(token, image.localPath());
            }
        }
        if (imageKey.isBlank()) {
            throw new IllegalArgumentException("飞书图片出站需要 imageKey 或 image 类型附件 localPath。");
        }
        return new FeishuMessagePayload("image", objectMapper.writeValueAsString(Map.of("image_key", imageKey)));
    }

    private FeishuMessagePayload filePayload(
            ChannelDefinition channel,
            Map<String, String> sourceMetadata,
            List<OutboundAttachment> attachments,
            String token) throws Exception {
        String fileKey = firstNonBlank(
                metadataValue(sourceMetadata, "feishu.fileKey", "feishu.fileKeyEnv"),
                metadataValue(sourceMetadata, "fileKey", "fileKeyEnv"),
                metadataValue(channel, "feishu.fileKey", "feishu.fileKeyEnv"));
        OutboundAttachment file = firstAttachment(attachments, "file");
        if (fileKey.isBlank()) {
            fileKey = firstNonBlank(file == null ? "" : file.platformKey(), file == null ? "" : file.extra("fileKey"));
            if (fileKey.isBlank() && file != null && !file.localPath().isBlank()) {
                fileKey = uploadFile(token, file.localPath(), file.fileName(), firstNonBlank(file.extra("fileType"), file.extra("feishuFileType"), "stream"));
            }
        }
        if (fileKey.isBlank()) {
            throw new IllegalArgumentException("飞书文件出站需要 fileKey 或 file 类型附件 localPath。");
        }
        return new FeishuMessagePayload("file", objectMapper.writeValueAsString(Map.of("file_key", fileKey)));
    }

    private List<FeishuMessagePayload> attachmentPayloads(
            ChannelDefinition channel,
            Map<String, String> sourceMetadata,
            List<OutboundAttachment> attachments,
            String text,
            String token) throws Exception {
        List<FeishuMessagePayload> payloads = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            payloads.add(looksLikeMarkdown(text) ? postPayload(text, true) : textPayload(text));
        }
        if (hasAnyValue(sourceMetadata, "feishu.cardJson", "cardJson", "interactiveCardJson")) {
            payloads.add(interactivePayload(channel, sourceMetadata, text));
        }
        for (OutboundAttachment attachment : attachments) {
            String type = attachment.type();
            if ("image".equals(type)) {
                String imageKey = firstNonBlank(attachment.platformKey(), attachment.extra("imageKey"));
                if (imageKey.isBlank() && !attachment.localPath().isBlank()) {
                    imageKey = uploadImage(token, attachment.localPath());
                }
                if (!imageKey.isBlank()) {
                    payloads.add(new FeishuMessagePayload("image", objectMapper.writeValueAsString(Map.of("image_key", imageKey))));
                }
            } else if ("file".equals(type)) {
                String fileKey = firstNonBlank(attachment.platformKey(), attachment.extra("fileKey"));
                if (fileKey.isBlank() && !attachment.localPath().isBlank()) {
                    fileKey = uploadFile(token, attachment.localPath(), attachment.fileName(), firstNonBlank(attachment.extra("fileType"), attachment.extra("feishuFileType"), "stream"));
                }
                if (!fileKey.isBlank()) {
                    payloads.add(new FeishuMessagePayload("file", objectMapper.writeValueAsString(Map.of("file_key", fileKey))));
                }
            }
        }
        return payloads;
    }

    private String uploadImage(String token, String localPath) throws Exception {
        Path path = checkedPath(localPath, "图片");
        AgentHttpResponse response = AgentHttpClient.upload(FEISHU_IMAGE_URL, "image", path,
                Map.of("image_type", "message"), Map.of("Authorization", "Bearer " + token), 60_000);
        if (!isSuccess(response)) {
            throw new IllegalStateException("飞书图片上传失败：" + truncate(stringValue(response.body()), 800));
        }
        return objectMapper.readTree(response.body()).path("data").path("image_key").asText("");
    }

    private String uploadFile(String token, String localPath, String fileName, String fileType) throws Exception {
        Path path = checkedPath(localPath, "文件");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("file_type", firstNonBlank(fileType, "stream"));
        form.put("file_name", firstNonBlank(fileName, path.getFileName().toString()));
        AgentHttpResponse response = AgentHttpClient.upload(FEISHU_FILE_URL, "file", path, form,
                Map.of("Authorization", "Bearer " + token), 60_000);
        if (!isSuccess(response)) {
            throw new IllegalStateException("飞书文件上传失败：" + truncate(stringValue(response.body()), 800));
        }
        return objectMapper.readTree(response.body()).path("data").path("file_key").asText("");
    }

    private Path checkedPath(String localPath, String label) {
        Path path = Path.of(localPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("飞书" + label + "上传文件不存在：" + localPath);
        }
        return path;
    }

    private List<OutboundAttachment> outboundAttachments(Map<String, String> metadata) throws Exception {
        String json = firstNonBlank(metadata.get("attachments"), metadata.get("feishu.attachments"));
        if (json.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(json);
        List<OutboundAttachment> attachments = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode item : root) {
                attachments.add(toAttachment(item));
            }
        } else if (root.isObject()) {
            attachments.add(toAttachment(root));
        }
        return attachments.stream().filter(attachment -> !attachment.type().isBlank()).toList();
    }

    private OutboundAttachment toAttachment(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        String rawType = firstNonBlank(values.get("type"), values.get("mediaType"), values.get("messageType"));
        String type = normalizeAttachmentType(rawType, values.get("mimeType"));
        return new OutboundAttachment(
                type,
                firstNonBlank(values.get("localPath"), values.get("path"), values.get("filePath")),
                firstNonBlank(values.get("fileName"), values.get("name")),
                firstNonBlank(values.get("imageKey"), values.get("fileKey"), values.get("key"), values.get("mediaKey")),
                values);
    }

    private String normalizeAttachmentType(String rawType, String mimeType) {
        String type = stringValue(rawType).toLowerCase(Locale.ROOT);
        String mime = stringValue(mimeType).toLowerCase(Locale.ROOT);
        if (type.contains("image") || mime.startsWith("image/")) {
            return "image";
        }
        if (type.contains("file") || type.contains("document") || !mime.isBlank()) {
            return "file";
        }
        return type;
    }

    private OutboundAttachment firstAttachment(List<OutboundAttachment> attachments, String type) {
        return attachments.stream().filter(attachment -> type.equals(attachment.type())).findFirst().orElse(null);
    }

    private Map<String, Object> postContent(String text, boolean markdown) {
        List<List<Map<String, String>>> content = new ArrayList<>();
        if (markdown) {
            // 飞书 Markdown 必须走 post 富文本里的 md 标签，text 消息只会按普通文本展示。
            content.add(List.of(Map.of("tag", "md", "text", text)));
            return Map.of("zh_cn", Map.of("title", "ClawAgent", "content", content));
        }
        // 普通 post 仍按纯文本段落发送，避免误把用户原文当 Markdown 渲染。
        for (String line : text.split("\\R", -1)) {
            content.add(List.of(Map.of("tag", "text", "text", line.isBlank() ? " " : line)));
        }
        return Map.of("zh_cn", Map.of("title", "ClawAgent", "content", content));
    }

    private boolean looksLikeMarkdown(String text) {
        String value = stringValue(text);
        if (value.contains("**") || value.contains("__") || value.contains("`") || value.contains("](")) {
            return true;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("> ")) {
                return true;
            }
            if (trimmed.matches("\\d+\\.\\s+.+")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> defaultCard(String text) {
        Map<String, Object> title = Map.of("tag", "plain_text", "content", "ClawAgent");
        Map<String, Object> bodyText = Map.of("tag", "plain_text", "content", firstNonBlank(text, " "));
        Map<String, Object> element = Map.of("tag", "div", "text", bodyText);
        return Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of("template", "blue", "title", title),
                "elements", List.of(element));
    }

    private AgentHttpRequest jsonPost(String url, Object body) throws Exception {
        return AgentHttpRequest.post(url)
                .timeoutMs(20_000)
                .contentType("application/json; charset=utf-8")
                .body(objectMapper.writeValueAsString(body));
    }

    private boolean isSuccess(AgentHttpResponse response) {
        if (!response.is2xx()) {
            return false;
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            return !json.has("code") || json.path("code").asInt(-1) == 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    private String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
        if (channel == null || channel.metadata() == null) {
            return "";
        }
        return metadataValue(channel.metadata(), directKey, envKey);
    }

    private String metadataValue(Map<String, String> metadata, String directKey, String envKey) {
        if (metadata == null) {
            return "";
        }
        String direct = stringValue(metadata.get(directKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = stringValue(metadata.get(envKey));
        return envName.isBlank() ? "" : stringValue(System.getenv(envName));
    }

    private boolean hasAnyValue(Map<String, String> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!stringValue(metadata.get(key)).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String cachedTenantToken(ChannelDefinition channel) {
        String appId = metadataValue(channel, "appId", "appIdEnv");
        String appSecret = metadataValue(channel, "appSecret", "appSecretEnv");
        if (appId.isBlank() || appSecret.isBlank()) {
            return "";
        }
        String cacheKey = appId + ":" + appSecret.hashCode();
        Instant now = Instant.now();
        TokenCacheEntry cached = tenantTokens.get(cacheKey);
        if (cached != null && cached.validAt(now)) {
            return cached.token();
        }
        try {
            AgentHttpResponse response = AgentHttpClient.execute(jsonPost(FEISHU_TOKEN_URL,
                    Map.of("app_id", appId, "app_secret", appSecret)));
            if (!isSuccess(response)) {
                tenantTokens.put(cacheKey, TokenCacheEntry.empty());
                return "";
            }
            JsonNode json = objectMapper.readTree(response.body());
            String token = json.path("tenant_access_token").asText("");
            long expireSeconds = json.path("expire").asLong(7200L);
            Instant expiresAt = now.plusSeconds(Math.max(60L, expireSeconds - 120L));
            tenantTokens.put(cacheKey, token.isBlank() ? TokenCacheEntry.empty() : new TokenCacheEntry(token, expiresAt));
            return token;
        } catch (Exception e) {
            tenantTokens.put(cacheKey, TokenCacheEntry.empty());
            return "";
        }
    }

    private String normalizeMessageType(String value) {
        String type = stringValue(value).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "rich-text", "rich_text" -> "post";
            case "card" -> "interactive";
            case "multi-modal", "multi_modal" -> "multimodal";
            default -> type.isBlank() ? "text" : type;
        };
    }

    private List<String> missingMetadata(String key, String value, String... rest) {
        List<String> missing = new ArrayList<>();
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
        if (rest != null) {
            for (int i = 0; i + 1 < rest.length; i += 2) {
                if (rest[i + 1] == null || rest[i + 1].isBlank()) {
                    missing.add(rest[i]);
                }
            }
        }
        return missing;
    }

    private String firstNonBlank(String... values) {
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

    private String safeType(ChannelDefinition channel) {
        return channel.type() == null ? "" : channel.type().trim().toLowerCase();
    }

    private String safeError(String prefix, Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = e.getClass().getSimpleName();
        }
        return prefix + "：" + detail;
    }

    private String truncate(String value, int maxLength) {
        String text = stringValue(value);
        return text.length() > maxLength ? text.substring(0, maxLength) + "...[truncated]" : text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record FeishuMessagePayload(String messageType, String contentJson) {
    }

    private record OutboundAttachment(String type, String localPath, String fileName, String platformKey, Map<String, String> metadata) {
        String extra(String key) {
            return metadata == null ? "" : stringValueStatic(metadata.get(key));
        }

        private static String stringValueStatic(Object value) {
            return value == null ? "" : String.valueOf(value).trim();
        }
    }

    private record TokenCacheEntry(String token, Instant expiresAt) {
        static TokenCacheEntry empty() {
            return new TokenCacheEntry("", Instant.EPOCH);
        }

        boolean validAt(Instant now) {
            return token != null && !token.isBlank() && expiresAt != null && expiresAt.isAfter(now);
        }
    }
}
