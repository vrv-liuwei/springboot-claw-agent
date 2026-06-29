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
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
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
        putIfPresent(metadata, "channel.eventId", event == null ? "" : event.getEventId());
        putIfPresent(metadata, "channel.eventType", event == null ? "" : event.getEventType());
        putIfPresent(metadata, "channel.senderNick", textAt(data, "senderNick"));
        putIfPresent(metadata, "dingtalk.sessionWebhook", textAt(data, "sessionWebhook"));
        putIfPresent(metadata, "dingtalk.sessionWebhookExpiredTime", textAt(data, "sessionWebhookExpiredTime"));
        putIfPresent(metadata, "dingtalk.conversationType", textAt(data, "conversationType"));
        String messageType = firstNonBlank(textAt(data, "msgtype"), textAt(data, "messageType"), event == null ? "" : event.getEventType(), "event");
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
                firstNonBlank(textAt(data, "conversationId"), textAt(data, "openConversationId"), event == null ? "" : event.getEventCorpId(), "default"),
                firstNonBlank(textAt(data, "senderStaffId"), textAt(data, "senderId"), textAt(data, "userId"), event == null ? "" : event.getEventUnifiedAppId(), "external"),
                messageType,
                text,
                metadata,
                rawPayload);
    }

    public ChannelInboundMessage toInboundMessage(ChannelDefinition channel, ChatbotMessage message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel.adapter", MODE);
        putIfPresent(metadata, "channel.eventType", DingTalkStreamTopics.BOT_MESSAGE_TOPIC);
        putIfPresent(metadata, "channel.senderNick", message == null ? "" : message.getSenderNick());
        putIfPresent(metadata, "dingtalk.sessionWebhook", message == null ? "" : message.getSessionWebhook());
        putIfPresent(metadata, "dingtalk.sessionWebhookExpiredTime",
                message == null || message.getSessionWebhookExpiredTime() == null ? "" : String.valueOf(message.getSessionWebhookExpiredTime()));
        putIfPresent(metadata, "dingtalk.conversationType", message == null ? "" : message.getConversationType());
        putIfPresent(metadata, "dingtalk.msgId", message == null ? "" : message.getMsgId());
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("source", MODE);
        rawPayload.put("topic", DingTalkStreamTopics.BOT_MESSAGE_TOPIC);
        rawPayload.put("data", message);
        // 机器人消息由 SDK 直接反序列化，优先取 text/content.content，非文本消息保留类型和原始对象。
        return new ChannelInboundMessage(
                channel.id(),
                firstNonBlank(message == null ? "" : message.getConversationId(), "default"),
                firstNonBlank(message == null ? "" : message.getSenderStaffId(), message == null ? "" : message.getSenderId(), "external"),
                firstNonBlank(message == null ? "" : message.getMsgtype(), "event"),
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
