package com.github.clawagent.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import com.lark.oapi.ws.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.github.clawagent.channel.ChannelMessageDeduplicator;

/**
 * 飞书/Lark SDK 长连接客户端。
 */
public class FeishuStreamClient {
    public static final String MODE = "feishu-long-connection";
    private static final Logger log = LoggerFactory.getLogger(FeishuStreamClient.class);

    private final ChannelRouter channelRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChannelMessageDeduplicator messageDeduplicator = new ChannelMessageDeduplicator(Duration.ofMinutes(10));

    public FeishuStreamClient(ChannelRouter channelRouter) {
        this.channelRouter = channelRouter;
    }

    public ChannelStreamHandle start(ChannelDefinition channel) throws Exception {
        String appId = metadataValue(channel, "appId", "appIdEnv");
        String appSecret = metadataValue(channel, "appSecret", "appSecretEnv");
        EventDispatcher dispatcher = EventDispatcher
                .newBuilder(metadataValue(channel, "verificationToken", "verificationTokenEnv"),
                        metadataValue(channel, "encryptKey", "encryptKeyEnv"))
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        String messageKey = messageKey(channel, event);
                        if (messageDeduplicator.isDuplicate(messageKey)) {
                            log.info("feishu stream duplicate ignored channelId={} messageKey={}", channel.id(), messageKey);
                            return;
                        }
                        // 飞书长连接事件应快速返回；Agent 执行和出站回写放到后台，避免平台因处理慢重投同一 message_id。
                        CompletableFuture.runAsync(() -> routeMessage(channel, event, messageKey));
                    }
                })
                .build();
        Client client = new Client.Builder(appId, appSecret)
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .build();
        client.start();
        // 飞书 SDK 当前版本没有 public stop；保留 client 引用，避免被提前回收并阻止重复启动。
        return new ChannelStreamHandle(MODE, client, null);
    }

    void routeMessage(ChannelDefinition channel, P2MessageReceiveV1 event, String messageKey) {
        try {
            channelRouter.receive(channel.id(), toInboundMessage(channel, event));
        } catch (Exception e) {
            log.error("feishu stream route failed channelId={} messageKey={}", channel.id(), messageKey, e);
        }
    }

    private String messageKey(ChannelDefinition channel, P2MessageReceiveV1 event) {
        P2MessageReceiveV1Data data = event == null ? null : event.getEvent();
        EventMessage message = data == null ? null : data.getMessage();
        String messageId = message == null ? "" : stringValue(message.getMessageId());
        if (messageId.isBlank()) {
            return "";
        }
        return stringValue(channel == null ? "" : channel.id()) + ":" + messageId;
    }

    ChannelInboundMessage toInboundMessage(ChannelDefinition channel, P2MessageReceiveV1 event) {
        P2MessageReceiveV1Data data = event == null ? null : event.getEvent();
        EventMessage message = data == null ? null : data.getMessage();
        EventSender sender = data == null ? null : data.getSender();
        UserId senderId = sender == null ? null : sender.getSenderId();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel.adapter", MODE);
        putIfPresent(metadata, "channel.messageId", message == null ? "" : message.getMessageId());
        putIfPresent(metadata, "channel.eventType", event == null || event.getHeader() == null ? "" : event.getHeader().getEventType());
        String messageType = firstNonBlank(message == null ? "" : message.getMessageType(), "text");
        String rawContent = message == null ? "" : message.getContent();
        JsonNode contentJson = parseContent(rawContent);
        putFeishuMediaMetadata(metadata, messageType, contentJson);
        String content = firstNonBlank(extractText(contentJson), mediaPlaceholder(messageType, contentJson), rawContent);
        // SDK 收到的 content 是飞书 JSON 字符串；文本只取正文，图片/文件统一放入 metadata.attachments 供出站 auto 复用。
        return new ChannelInboundMessage(
                channel.id(),
                firstNonBlank(message == null ? "" : message.getChatId(), "default"),
                firstNonBlank(senderId == null ? "" : senderId.getUserId(), senderId == null ? "" : senderId.getOpenId(), "external"),
                messageType,
                content,
                metadata,
                Map.of("source", MODE));
    }

    private JsonNode parseContent(String content) {
        if (content == null || content.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(content);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String extractText(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return "";
        }
        JsonNode text = root.path("text");
        return text.isTextual() ? text.asText() : "";
    }

    private String mediaPlaceholder(String messageType, JsonNode contentJson) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        if ("image".equals(normalized) && !textValue(contentJson, "image_key").isBlank()) {
            return "[飞书图片] " + textValue(contentJson, "image_key");
        }
        if ("file".equals(normalized)) {
            return "[飞书文件] " + firstNonBlank(
                    textValue(contentJson, "file_name"),
                    textValue(contentJson, "fileName"),
                    textValue(contentJson, "file_key"),
                    textValue(contentJson, "fileKey"),
                    "未提供文件名");
        }
        return "";
    }

    private void putFeishuMediaMetadata(Map<String, String> metadata, String messageType, JsonNode contentJson) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        if ("image".equals(normalized)) {
            String imageKey = textValue(contentJson, "image_key");
            if (!imageKey.isBlank()) {
                metadata.put("channel.feishu.imageKey", imageKey);
                metadata.put("attachments", attachmentJson("image", Map.of("imageKey", imageKey)));
            }
            return;
        }
        if ("file".equals(normalized)) {
            String fileKey = firstNonBlank(textValue(contentJson, "file_key"), textValue(contentJson, "fileKey"));
            if (!fileKey.isBlank()) {
                String fileName = firstNonBlank(textValue(contentJson, "file_name"), textValue(contentJson, "fileName"));
                Map<String, String> attachment = new LinkedHashMap<>();
                attachment.put("fileKey", fileKey);
                if (!fileName.isBlank()) {
                    attachment.put("fileName", fileName);
                }
                metadata.put("channel.feishu.fileKey", fileKey);
                putIfPresent(metadata, "channel.feishu.fileName", fileName);
                metadata.put("attachments", attachmentJson("file", attachment));
            }
        }
    }

    private String attachmentJson(String type, Map<String, String> values) {
        Map<String, String> attachment = new LinkedHashMap<>();
        attachment.put("type", type);
        attachment.put("source", "feishu");
        attachment.putAll(values);
        try {
            return objectMapper.writeValueAsString(List.of(attachment));
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
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

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
