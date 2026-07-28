package com.github.clawagent.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.clawagent.channel.ChannelEventMetadataSupport;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.lark.oapi.event.model.Header;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.github.clawagent.channel.ChannelMessageDeduplicator;

/**
 * 飞书/Lark SDK 长连接客户端。
 */
public class FeishuStreamClient {
    public static final String MODE = "feishu-long-connection";
    private static final Logger log = LoggerFactory.getLogger(FeishuStreamClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

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
        Header header = event == null ? null : event.getHeader();
        Map<String, String> metadata = new LinkedHashMap<>();
        String messageType = firstNonBlank(message == null ? "" : message.getMessageType(), "text");
        String conversationId = firstNonBlank(message == null ? "" : message.getChatId(), "default");
        String externalUserId = firstNonBlank(senderId == null ? "" : senderId.getUserId(), senderId == null ? "" : senderId.getOpenId(), "external");
        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.ofEntries(
                Map.entry(ChannelEventMetadataSupport.ADAPTER, MODE),
                Map.entry(ChannelEventMetadataSupport.EVENT_SOURCE, "stream"),
                Map.entry(ChannelEventMetadataSupport.EVENT_TYPE, header == null ? "" : stringValue(header.getEventType())),
                Map.entry(ChannelEventMetadataSupport.EVENT_ID, header == null ? "" : stringValue(header.getEventId())),
                Map.entry(ChannelEventMetadataSupport.EVENT_CREATE_TIME, header == null ? "" : stringValue(header.getCreateTime())),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_ID, message == null ? "" : stringValue(message.getMessageId())),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_CREATE_TIME, message == null ? "" : stringValue(message.getCreateTime())),
                Map.entry(ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, messageType),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_ID, conversationId),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_TYPE, message == null ? "" : stringValue(message.getChatType())),
                Map.entry(ChannelEventMetadataSupport.EXTERNAL_USER_ID, externalUserId),
                Map.entry(ChannelEventMetadataSupport.TENANT_KEY, firstNonBlank(sender == null ? "" : sender.getTenantKey(), header == null ? "" : header.getTenantKey()))
        ));
        metadata.put("channel.adapter", MODE);
        putIfPresent(metadata, "channel.messageId", message == null ? "" : message.getMessageId());
        putIfPresent(metadata, "channel.eventType", header == null ? "" : header.getEventType());
        putIfPresent(metadata, "channel.eventId", header == null ? "" : header.getEventId());
        putIfPresent(metadata, "channel.eventCreateTime", header == null ? "" : header.getCreateTime());
        putIfPresent(metadata, "channel.messageCreateTime", message == null ? "" : message.getCreateTime());
        putIfPresent(metadata, "channel.conversationType", message == null ? "" : message.getChatType());
        putIfPresent(metadata, "channel.senderTenantKey", firstNonBlank(
                sender == null ? "" : sender.getTenantKey(),
                header == null ? "" : header.getTenantKey()));
        String rawContent = message == null ? "" : message.getContent();
        JsonNode contentJson = parseContent(rawContent);
        Map<String, Object> contentMap = contentMap(contentJson);
        putIfPresent(metadata, "channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.feishu.imageKey", stringValue(contentMap.get("image_key")));
        putIfPresent(metadata, "channel.feishu.fileKey", firstNonBlank(
                stringValue(contentMap.get("file_key")),
                stringValue(contentMap.get("fileKey"))));
        putIfPresent(metadata, "channel.feishu.fileName", firstNonBlank(
                stringValue(contentMap.get("file_name")),
                stringValue(contentMap.get("fileName"))));
        FeishuInboundAdapter.putAttachmentMetadata(metadata, channel, channel.id(),
                message == null ? "" : message.getMessageId(), messageType, contentMap);
        String content = firstNonBlank(extractText(contentJson), mediaPlaceholder(messageType, contentJson), rawContent);
        // SDK 收到的 content 是飞书 JSON 字符串；文本只取正文，媒体和富文本统一走 HTTP 入站同一套 attachments 规则。
        return new ChannelInboundMessage(
                channel.id(),
                conversationId,
                externalUserId,
                messageType,
                content,
                metadata,
                Map.of(
                        "source", MODE,
                        "eventId", header == null ? "" : stringValue(header.getEventId())));
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
        if ("audio".equals(normalized) || "media".equals(normalized)) {
            // 长连接媒体消息同样进入 attachment metadata，这里只给模型一个稳定、可读的摘要。
            return "[飞书音视频] " + firstNonBlank(
                    textValue(contentJson, "file_key"),
                    textValue(contentJson, "fileKey"),
                    textValue(contentJson, "file_name"),
                    textValue(contentJson, "fileName"),
                    "未提供 file_key");
        }
        if ("interactive".equals(normalized) || "card".equals(normalized)
                || "post".equals(normalized) || "rich_text".equals(normalized)) {
            return "[飞书卡片/富文本] " + firstNonBlank(
                    richTitle(contentJson),
                    textValue(contentJson, "title"),
                    "请在平台查看完整内容");
        }
        return "";
    }

    private String richTitle(JsonNode contentJson) {
        JsonNode title = contentJson == null ? null : contentJson.path("header").path("title");
        if (title == null || title.isMissingNode()) {
            return "";
        }
        String content = textValue(title, "content");
        return content.isBlank() && title.isTextual() ? title.asText().trim() : content;
    }

    private Map<String, Object> contentMap(JsonNode contentJson) {
        if (contentJson == null || contentJson.isMissingNode() || !contentJson.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(contentJson, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
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
