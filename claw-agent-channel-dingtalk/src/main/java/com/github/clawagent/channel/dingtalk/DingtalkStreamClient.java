package com.github.clawagent.channel.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.message.GenericOpenDingTalkEvent;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelEventMetadataSupport;
import com.github.clawagent.channel.ChannelMediaSupport;
import com.github.clawagent.channel.ChannelRichRenderSupport;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉 Stream SDK 客户端。
 */
public class DingtalkStreamClient {
    public static final String MODE = "dingtalk-stream";
    private static final Logger log = LoggerFactory.getLogger(DingtalkStreamClient.class);

    private final ChannelRouter channelRouter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingtalkStreamClient(ChannelRouter channelRouter) {
        this.channelRouter = channelRouter;
    }

    public ChannelStreamHandle start(ChannelDefinition channel) throws Exception {
        String clientId = firstNonBlank(
                metadataValue(channel, "clientId", "clientIdEnv"),
                metadataValue(channel, "appKey", "appKeyEnv"),
                metadataValue(channel, "appId", "appIdEnv"));
        String clientSecret = firstNonBlank(
                metadataValue(channel, "clientSecret", "clientSecretEnv"),
                metadataValue(channel, "appSecret", "appSecretEnv"));
        log.info("dingtalk stream starting channelId={} clientId={} secretConfigured={}",
                channel == null ? "" : channel.id(), mask(clientId), !clientSecret.isBlank());
        OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
                .credential(new AuthClientCredential(clientId, clientSecret))
                // 机器人收消息需要订阅专用 callback topic；订阅 EVENT/* 会导致机器人 Stream 建连参数不匹配。
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, (ChatbotMessage message) -> {
                    routeBotMessage(channel, message);
                    return Map.of();
                })
                .build();
        client.start();
        return new ChannelStreamHandle(MODE, client, client::stop);
    }

    private void routeBotMessage(ChannelDefinition channel, ChatbotMessage message) {
        channelRouter.receive(channel.id(), toInboundMessage(channel, message));
    }

    private EventAckStatus routeEvent(ChannelDefinition channel, GenericOpenDingTalkEvent event) {
        channelRouter.receive(channel.id(), toInboundMessage(channel, event));
        return EventAckStatus.SUCCESS;
    }

    public ChannelInboundMessage toInboundMessage(ChannelDefinition channel, GenericOpenDingTalkEvent event) {
        JsonNode data = dingtalkData(event);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel.adapter", MODE);
        String messageType = firstNonBlank(textAt(data, "msgtype"), textAt(data, "messageType"), event == null ? "" : event.getEventType(), "event");
        String messageId = firstNonBlank(textAt(data, "msgId"), textAt(data, "messageId"), event == null ? "" : event.getEventId());
        String conversationId = firstNonBlank(textAt(data, "conversationId"), textAt(data, "openConversationId"), event == null ? "" : event.getEventCorpId(), "default");
        String externalUserId = firstNonBlank(textAt(data, "senderStaffId"), textAt(data, "senderId"), textAt(data, "userId"), event == null ? "" : event.getEventUnifiedAppId(), "external");
        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.ofEntries(
                Map.entry(ChannelEventMetadataSupport.ADAPTER, MODE),
                Map.entry(ChannelEventMetadataSupport.EVENT_SOURCE, "stream"),
                Map.entry(ChannelEventMetadataSupport.EVENT_TYPE, event == null ? "" : stringValue(event.getEventType())),
                Map.entry(ChannelEventMetadataSupport.EVENT_ID, event == null ? "" : stringValue(event.getEventId())),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_ID, messageId),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_CREATE_TIME, firstNonBlank(textAt(data, "createAt"), textAt(data, "createTime"), textAt(data, "msgCreateTime"))),
                Map.entry(ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, messageType),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_ID, conversationId),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_TYPE, textAt(data, "conversationType")),
                Map.entry(ChannelEventMetadataSupport.EXTERNAL_USER_ID, externalUserId),
                Map.entry(ChannelEventMetadataSupport.SENDER_NAME, textAt(data, "senderNick")),
                Map.entry(ChannelEventMetadataSupport.APP_ID, event == null ? "" : stringValue(event.getEventUnifiedAppId())),
                Map.entry(ChannelEventMetadataSupport.CORP_ID, event == null ? "" : stringValue(event.getEventCorpId()))
        ));
        putIfPresent(metadata, "channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.messageId", messageId);
        putIfPresent(metadata, "channel.eventId", event == null ? "" : event.getEventId());
        putIfPresent(metadata, "channel.eventType", event == null ? "" : event.getEventType());
        putIfPresent(metadata, "channel.messageCreateTime", firstNonBlank(
                textAt(data, "createAt"),
                textAt(data, "createTime"),
                textAt(data, "msgCreateTime")));
        putIfPresent(metadata, "channel.conversationType", textAt(data, "conversationType"));
        putIfPresent(metadata, "channel.senderNick", textAt(data, "senderNick"));
        putIfPresent(metadata, "dingtalk.sessionWebhook", textAt(data, "sessionWebhook"));
        putIfPresent(metadata, "dingtalk.sessionWebhookExpiredTime", textAt(data, "sessionWebhookExpiredTime"));
        putIfPresent(metadata, "dingtalk.conversationType", textAt(data, "conversationType"));
        putIfPresent(metadata, "dingtalk.msgId", messageId);
        putIfPresent(metadata, "channel.dingtalk.picUrl", firstNonBlank(textAt(data, "image", "picUrl"), textAt(data, "picUrl")));
        putIfPresent(metadata, "channel.dingtalk.fileName", firstNonBlank(textAt(data, "file", "fileName"), textAt(data, "fileName")));
        putIfPresent(metadata, "channel.dingtalk.downloadUrl", firstNonBlank(textAt(data, "file", "downloadUrl"), textAt(data, "downloadUrl")));
        putIfPresent(metadata, "channel.dingtalk.downloadCode", firstNonBlank(textAt(data, "file", "downloadCode"), textAt(data, "downloadCode")));
        putStreamAttachmentMetadata(metadata, channel, messageId, messageType, data);
        String text = firstNonBlank(
                textAt(data, "text", "content"),
                textAt(data, "message", "text"),
                textAt(data, "message", "content"),
                textAt(data, "content"),
                textAt(data, "msgContent"),
                data == null || data.isMissingNode() || data.isNull() ? "" : data.toString());
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("source", MODE);
        if (event != null) {
            rawPayload.put("eventId", event.getEventId());
            rawPayload.put("eventType", event.getEventType());
            rawPayload.put("eventCorpId", event.getEventCorpId());
            rawPayload.put("eventUnifiedAppId", event.getEventUnifiedAppId());
            rawPayload.put("data", event.getData());
        }
        // Stream 事件结构比自定义机器人更分散，先抽取常见字段；无法识别时保留原始 data 字符串。
        return new ChannelInboundMessage(
                channel.id(),
                conversationId,
                externalUserId,
                messageType,
                text,
                metadata,
                rawPayload);
    }

    public ChannelInboundMessage toInboundMessage(ChannelDefinition channel, ChatbotMessage message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel.adapter", MODE);
        String messageType = firstNonBlank(message == null ? "" : message.getMsgtype(), "event");
        String messageId = message == null ? "" : message.getMsgId();
        String conversationId = firstNonBlank(message == null ? "" : message.getConversationId(), "default");
        String externalUserId = firstNonBlank(message == null ? "" : message.getSenderStaffId(), message == null ? "" : message.getSenderId(), "external");
        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.ofEntries(
                Map.entry(ChannelEventMetadataSupport.ADAPTER, MODE),
                Map.entry(ChannelEventMetadataSupport.EVENT_SOURCE, "bot"),
                Map.entry(ChannelEventMetadataSupport.EVENT_TYPE, DingTalkStreamTopics.BOT_MESSAGE_TOPIC),
                Map.entry(ChannelEventMetadataSupport.EVENT_ID, messageId),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_ID, messageId),
                Map.entry(ChannelEventMetadataSupport.MESSAGE_CREATE_TIME, message == null || message.getCreateAt() == null ? "" : String.valueOf(message.getCreateAt())),
                Map.entry(ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, messageType),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_ID, conversationId),
                Map.entry(ChannelEventMetadataSupport.CONVERSATION_TYPE, message == null ? "" : stringValue(message.getConversationType())),
                Map.entry(ChannelEventMetadataSupport.EXTERNAL_USER_ID, externalUserId),
                Map.entry(ChannelEventMetadataSupport.SENDER_NAME, message == null ? "" : stringValue(message.getSenderNick()))
        ));
        putIfPresent(metadata, "channel.platformMessageType", messageType);
        putIfPresent(metadata, "channel.messageId", messageId);
        putIfPresent(metadata, "channel.eventId", messageId);
        putIfPresent(metadata, "channel.eventType", DingTalkStreamTopics.BOT_MESSAGE_TOPIC);
        putIfPresent(metadata, "channel.messageCreateTime",
                message == null || message.getCreateAt() == null ? "" : String.valueOf(message.getCreateAt()));
        putIfPresent(metadata, "channel.conversationType", message == null ? "" : message.getConversationType());
        putIfPresent(metadata, "channel.senderNick", message == null ? "" : message.getSenderNick());
        putIfPresent(metadata, "dingtalk.sessionWebhook", message == null ? "" : message.getSessionWebhook());
        putIfPresent(metadata, "dingtalk.sessionWebhookExpiredTime",
                message == null || message.getSessionWebhookExpiredTime() == null ? "" : String.valueOf(message.getSessionWebhookExpiredTime()));
        putIfPresent(metadata, "dingtalk.conversationType", message == null ? "" : message.getConversationType());
        putIfPresent(metadata, "dingtalk.msgId", messageId);
        putBotAttachmentMetadata(metadata, channel, message);
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("source", MODE);
        rawPayload.put("topic", DingTalkStreamTopics.BOT_MESSAGE_TOPIC);
        rawPayload.put("data", message);
        // 机器人消息由 SDK 直接反序列化，优先取 text/content.content，非文本消息保留类型和原始对象。
        return new ChannelInboundMessage(
                channel.id(),
                conversationId,
                externalUserId,
                messageType,
                botText(message),
                metadata,
                rawPayload);
    }

    private String botText(ChatbotMessage message) {
        if (message == null) {
            return "";
        }
        return firstNonBlank(contentText(message.getText()), contentText(message.getContent()), message.getMsgtype());
    }

    private String contentText(MessageContent content) {
        if (content == null) {
            return "";
        }
        return firstNonBlank(content.getContent(), content.getText(), content.getFileName(), content.getUnknownMsgType());
    }

    private void putStreamAttachmentMetadata(Map<String, String> metadata, ChannelDefinition channel,
                                             String messageId, String messageType, JsonNode data) {
        String normalized = messageType == null ? "" : messageType.trim().toLowerCase();
        List<Map<String, String>> attachments = new ArrayList<>();
        if ("image".equals(normalized) || "picture".equals(normalized)) {
            String picUrl = firstNonBlank(textAt(data, "image", "picUrl"), textAt(data, "picUrl"));
            String pictureDownloadCode = firstNonBlank(textAt(data, "image", "pictureDownloadCode"), textAt(data, "pictureDownloadCode"));
            if (!picUrl.isBlank()) {
                attachments.add(ChannelMediaSupport.download(channel, "dingtalk", "image", picUrl,
                        Map.of(), channel == null ? "" : channel.id(), messageId, picUrl, "dingtalk-image", 30000));
            } else if (!pictureDownloadCode.isBlank()) {
                attachments.add(downloadCodeAttachment(channel, messageId, "image", "pictureDownloadCode", pictureDownloadCode));
            }
        } else if ("file".equals(normalized)) {
            String fileName = firstNonBlank(textAt(data, "file", "fileName"), textAt(data, "fileName"), "dingtalk-file");
            String downloadUrl = firstNonBlank(textAt(data, "file", "downloadUrl"), textAt(data, "downloadUrl"));
            String downloadCode = firstNonBlank(textAt(data, "file", "downloadCode"), textAt(data, "downloadCode"));
            if (downloadUrl.isBlank()) {
                attachments.add(fileDownloadCodeAttachment(channel, messageId, fileName, downloadCode));
            } else {
                Map<String, String> attachment = ChannelMediaSupport.download(channel, "dingtalk", "file", downloadUrl,
                        Map.of(), channel == null ? "" : channel.id(), messageId, downloadCode, fileName, 30000);
                attachment.put("fileName", fileName);
                attachment.put("downloadCode", downloadCode);
                attachments.add(attachment);
            }
        } else if ("markdown".equals(normalized)) {
            JsonNode markdown = data.path("markdown");
            attachments.add(ChannelRichRenderSupport.richAttachment("dingtalk", "markdown", "markdown",
                    textAt(markdown, "title"), markdown));
        } else if ("actioncard".equals(normalized) || "feedcard".equals(normalized) || "card".equals(normalized)) {
            JsonNode card = data.path("actionCard").isMissingNode() || data.path("actionCard").isNull()
                    ? data.path("card") : data.path("actionCard");
            attachments.add(ChannelRichRenderSupport.richAttachment("dingtalk", "card", normalized,
                    firstNonBlank(textAt(card, "title"), textAt(data, "title")), card));
        }
        putAttachments(metadata, attachments);
    }

    private void putBotAttachmentMetadata(Map<String, String> metadata, ChannelDefinition channel, ChatbotMessage message) {
        if (message == null) {
            return;
        }
        String normalized = firstNonBlank(message.getMsgtype(), "").toLowerCase();
        String messageId = firstNonBlank(message.getMsgId(), "bot-message");
        MessageContent content = message.getContent() == null ? message.getText() : message.getContent();
        List<Map<String, String>> attachments = new ArrayList<>();
        if ("image".equals(normalized) || "picture".equals(normalized)) {
            String pictureDownloadCode = content == null ? "" : firstNonBlank(content.getPictureDownloadCode(), content.getDownloadCode());
            if (!pictureDownloadCode.isBlank()) {
                attachments.add(downloadCodeAttachment(channel, messageId, "image", "pictureDownloadCode", pictureDownloadCode));
            }
        } else if ("file".equals(normalized)) {
            attachments.add(fileDownloadCodeAttachment(channel, messageId,
                    content == null ? "dingtalk-file" : firstNonBlank(content.getFileName(), "dingtalk-file"),
                    content == null ? "" : content.getDownloadCode()));
        } else if ("richtext".equals(normalized) || "markdown".equals(normalized)) {
            Map<String, Object> rich = new LinkedHashMap<>();
            rich.put("text", content == null ? "" : firstNonBlank(content.getText(), content.getContent()));
            attachments.add(ChannelRichRenderSupport.richAttachment("dingtalk", "rich", normalized, "", rich));
        }
        putAttachments(metadata, attachments);
    }

    private Map<String, String> fileDownloadCodeAttachment(ChannelDefinition channel, String messageId,
                                                           String fileName, String downloadCode) {
        return DingtalkMediaDownloader.downloadCode(channel, "file", downloadCode,
                firstNonBlank(fileName, "dingtalk-file"), channel == null ? "" : channel.id(), messageId);
    }

    private Map<String, String> downloadCodeAttachment(ChannelDefinition channel, String messageId,
                                                       String type, String key, String value) {
        Map<String, String> attachment = DingtalkMediaDownloader.downloadCode(channel, type, value,
                "image".equals(type) ? "dingtalk-image" : "dingtalk-file", channel == null ? "" : channel.id(), messageId);
        attachment.put(key, value);
        return attachment;
    }

    private void putAttachments(Map<String, String> metadata, List<Map<String, String>> attachments) {
        ChannelMediaSupport.putAttachmentsMetadata(metadata, attachments);
    }

    private JsonNode dingtalkData(GenericOpenDingTalkEvent event) {
        if (event == null || event.getData() == null) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(event.getData().toJSONString());
        } catch (Exception ignored) {
            return objectMapper.nullNode();
        }
    }

    private String textAt(JsonNode root, String... path) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode current = root;
        for (String segment : path) {
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return "";
            }
        }
        if (current.isTextual() || current.isNumber() || current.isBoolean()) {
            return current.asText();
        }
        return "";
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

    private String mask(String value) {
        String text = stringValue(value);
        return text.length() <= 6 ? "******" : text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }
}
