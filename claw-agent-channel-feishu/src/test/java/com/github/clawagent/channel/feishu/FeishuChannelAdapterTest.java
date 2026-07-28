package com.github.clawagent.channel.feishu;

import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelInboundPayloadAdapter;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuChannelAdapterTest {

    @Test
    void convertsTextEventAndKeepsConfiguredChannelId() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-message",
                                "message_type", "text",
                                "content", "{\"text\":\"飞书消息\"}"
                        )
                )
        ));

        assertTrue(adapter.supports("lark"));
        assertEquals("corp-feishu", message.channelId());
        assertEquals("oc-chat", message.externalConversationId());
        assertEquals("ou-user", message.externalUserId());
        assertEquals("飞书消息", message.text());
        assertEquals("feishu", message.metadata().get("channel.adapter"));
        assertEquals("http", message.metadata().get("channel.eventSource"));
        assertEquals("message", message.metadata().get("channel.eventCategory"));
        assertEquals("im.message.receive_v1", message.metadata().get("channel.eventType"));
        assertEquals("om-message", message.metadata().get("channel.messageId"));
        assertEquals("oc-chat", message.metadata().get("channel.conversationId"));
        assertEquals("ou-user", message.metadata().get("channel.externalUserId"));
    }

    @Test
    void autoDetectsNonMessagePlatformEvent() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adaptWithResponse(
                new ChannelAdapterRegistry(List.of(new FeishuChannelAdapter())),
                null,
                "corp-feishu",
                Map.of(
                "header", Map.of(
                        "event_id", "ev-reaction-1",
                        "event_type", "im.message.reaction.created_v1",
                        "create_time", "1700000000004"),
                "event", Map.of(
                        "message_id", "om-reaction",
                        "chat_id", "oc-reaction",
                        "operator_id", "ou-operator")
        )).message();

        assertEquals("corp-feishu", message.channelId());
        assertEquals("oc-reaction", message.externalConversationId());
        assertEquals("ou-operator", message.externalUserId());
        assertEquals("im.message.reaction.created_v1", message.messageType());
        assertEquals("[飞书非文本消息] type=im.message.reaction.created_v1", message.text());
        assertEquals("reaction", message.metadata().get("channel.eventCategory"));
        assertEquals("created", message.metadata().get("channel.eventAction"));
        assertEquals("reaction.created", message.metadata().get("channel.eventSemantic"));
        assertEquals("ev-reaction-1", message.metadata().get("channel.eventId"));
        assertEquals("om-reaction", message.metadata().get("channel.messageId"));
        assertEquals("oc-reaction", message.metadata().get("channel.conversationId"));
        assertEquals("ou-operator", message.metadata().get("channel.externalUserId"));
    }

    @Test
    void returnsPlainChallengeAndChecksVerificationToken() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        ChannelDefinition channel = channel(Map.of("verificationToken", "token-1"));

        ChannelInboundPayloadResult result = adapter.adaptInbound(channel, "feishu", Map.of(
                "challenge", "challenge-1",
                "token", "token-1",
                "type", "url_verification"
        ));

        assertTrue(result.hasImmediateResponse());
        assertEquals(Map.of("challenge", "challenge-1"), result.responseBody());
        assertThrows(IllegalArgumentException.class, () -> adapter.adaptInbound(channel, "feishu", Map.of(
                "challenge", "challenge-1",
                "token", "bad-token",
                "type", "url_verification"
        )));
    }

    @Test
    void decryptsEncryptedChallenge() throws Exception {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        ChannelDefinition channel = channel(Map.of("encryptKey", "encrypt-key-1", "verificationToken", "token-1"));
        String encrypted = encryptFeishuPayload("{\"challenge\":\"challenge-2\",\"token\":\"token-1\",\"type\":\"url_verification\"}", "encrypt-key-1");

        ChannelInboundPayloadResult result = adapter.adaptInbound(channel, "feishu", Map.of("encrypt", encrypted));

        assertTrue(result.hasImmediateResponse());
        assertEquals(Map.of("challenge", "challenge-2"), result.responseBody());
    }

    @Test
    void extractsImageEventAsPlaceholderText() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-image",
                                "message_type", "image",
                                "content", "{\"image_key\":\"img_v2_abc\"}"
                        )
                )
        ));

        assertEquals("image", message.messageType());
        assertEquals("[飞书图片] img_v2_abc", message.text());
        assertEquals("img_v2_abc", message.metadata().get("channel.feishu.imageKey"));
        assertTrue(message.metadata().get("attachments").contains("img_v2_abc"));
        assertTrue(message.metadata().get("attachments").contains("missing-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("image", message.metadata().get("channel.attachmentTypes"));
        assertEquals("skipped", message.metadata().get("channel.attachmentDownloadStatuses"));
    }

    @Test
    void mapsInteractiveCardAsRichMetadataAttachment() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1", "event_id", "ev-card", "create_time", "1700000000000"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-card",
                                "create_time", "1700000000001",
                                "chat_type", "group",
                                "message_type", "interactive",
                                "content", """
                                        {
                                          "header": {"title": {"content": "审批卡片"}},
                                          "elements": [
                                            {"tag": "div", "text": {"content": "请确认部署"}},
                                            {"tag": "action", "actions": [{"text": {"content": "批准"}}]}
                                          ]
                                        }
                                        """
                        )
                )
        ));

        String attachments = message.metadata().get("attachments");
        assertEquals("interactive", message.messageType());
        assertEquals("[飞书卡片/富文本] 审批卡片", message.text());
        assertEquals("ev-card", message.metadata().get("channel.eventId"));
        assertEquals("1700000000000", message.metadata().get("channel.eventCreateTime"));
        assertEquals("1700000000001", message.metadata().get("channel.messageCreateTime"));
        assertEquals("group", message.metadata().get("channel.conversationType"));
        assertTrue(attachments.contains("\"type\":\"rich\""));
        assertTrue(attachments.contains("审批卡片"));
        assertTrue(attachments.contains("请确认部署"));
        assertTrue(attachments.contains("\"renderStatus\":\"rendered\""));
        assertTrue(attachments.contains("\"renderFormat\":\"markdown\""));
        assertTrue(attachments.contains("\"renderText\""));
        assertTrue(attachments.contains("批准"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("rich", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void normalizesStreamFileEventWithStandardMetadataAndAttachments() {
        FeishuStreamClient client = new FeishuStreamClient(null);
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        Header header = new Header();
        header.setEventId("ev-stream-1");
        header.setEventType("im.message.receive_v1");
        header.setCreateTime("1700000000000");
        header.setTenantKey("tenant-1");
        event.setHeader(header);
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        EventMessage sdkMessage = new EventMessage();
        sdkMessage.setChatId("oc-stream");
        sdkMessage.setMessageId("om-stream-file");
        sdkMessage.setCreateTime("1700000000001");
        sdkMessage.setChatType("group");
        sdkMessage.setMessageType("file");
        sdkMessage.setContent("{\"file_key\":\"file_v2_abc\",\"file_name\":\"需求说明.pdf\"}");
        EventSender sender = new EventSender();
        UserId senderId = new UserId();
        senderId.setUserId("ou-stream-user");
        sender.setSenderId(senderId);
        sender.setTenantKey("tenant-1");
        data.setMessage(sdkMessage);
        data.setSender(sender);
        event.setEvent(data);

        ChannelInboundMessage message = client.toInboundMessage(channel(Map.of()), event);

        assertEquals("feishu", message.channelId());
        assertEquals("oc-stream", message.externalConversationId());
        assertEquals("ou-stream-user", message.externalUserId());
        assertEquals("file", message.messageType());
        assertEquals("[飞书文件] 需求说明.pdf", message.text());
        assertEquals("om-stream-file", message.metadata().get("channel.messageId"));
        assertEquals("stream", message.metadata().get("channel.eventSource"));
        assertEquals("message", message.metadata().get("channel.eventCategory"));
        assertEquals("ev-stream-1", message.metadata().get("channel.eventId"));
        assertEquals("1700000000000", message.metadata().get("channel.eventCreateTime"));
        assertEquals("1700000000001", message.metadata().get("channel.messageCreateTime"));
        assertEquals("group", message.metadata().get("channel.conversationType"));
        assertEquals("oc-stream", message.metadata().get("channel.conversationId"));
        assertEquals("ou-stream-user", message.metadata().get("channel.externalUserId"));
        assertEquals("tenant-1", message.metadata().get("channel.tenantKey"));
        assertEquals("file_v2_abc", message.metadata().get("channel.feishu.fileKey"));
        assertEquals("需求说明.pdf", message.metadata().get("channel.feishu.fileName"));
        assertTrue(message.metadata().get("attachments").contains("file_v2_abc"));
        assertTrue(message.metadata().get("attachments").contains("missing-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("file", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void normalizesStreamMediaEventWithReadablePlaceholder() {
        FeishuStreamClient client = new FeishuStreamClient(null);
        P2MessageReceiveV1 event = streamEvent("media", "om-stream-media",
                "{\"file_key\":\"media_v2_stream\",\"file_name\":\"演示.mp4\"}");

        ChannelInboundMessage message = client.toInboundMessage(channel(Map.of()), event);

        assertEquals("media", message.messageType());
        assertEquals("[飞书音视频] media_v2_stream", message.text());
        assertEquals("media_v2_stream", message.metadata().get("channel.feishu.fileKey"));
        assertEquals("演示.mp4", message.metadata().get("channel.feishu.fileName"));
        assertTrue(message.metadata().get("attachments").contains("\"type\":\"media\""));
        assertEquals("1", message.metadata().get("channel.mediaAttachmentCount"));
    }

    @Test
    void normalizesStreamInteractiveEventWithReadablePlaceholder() {
        FeishuStreamClient client = new FeishuStreamClient(null);
        P2MessageReceiveV1 event = streamEvent("interactive", "om-stream-card",
                "{\"header\":{\"title\":{\"content\":\"发布确认\"}},\"elements\":[]}");

        ChannelInboundMessage message = client.toInboundMessage(channel(Map.of()), event);

        assertEquals("interactive", message.messageType());
        assertEquals("[飞书卡片/富文本] 发布确认", message.text());
        assertTrue(message.metadata().get("attachments").contains("\"type\":\"rich\""));
        assertTrue(message.metadata().get("attachments").contains("发布确认"));
    }

    @Test
    void extractsAudioEventAsMediaAttachmentMetadata() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-audio",
                                "message_type", "audio",
                                "content", "{\"file_key\":\"audio_v2_abc\",\"file_name\":\"voice.amr\"}"
                        )
                )
        ));

        assertEquals("audio", message.messageType());
        assertEquals("[飞书音视频] audio_v2_abc", message.text());
        assertEquals("audio_v2_abc", message.metadata().get("channel.feishu.fileKey"));
        assertEquals("voice.amr", message.metadata().get("channel.feishu.fileName"));
        assertTrue(message.metadata().get("attachments").contains("\"type\":\"audio\""));
        assertTrue(message.metadata().get("attachments").contains("missing-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("1", message.metadata().get("channel.mediaAttachmentCount"));
        assertEquals("audio", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void extractsMediaEventAsMediaAttachmentMetadata() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-media",
                                "message_type", "media",
                                "content", "{\"file_key\":\"media_v2_abc\",\"file_name\":\"demo.mp4\"}"
                        )
                )
        ));

        assertEquals("media", message.messageType());
        assertEquals("[飞书音视频] media_v2_abc", message.text());
        assertEquals("media_v2_abc", message.metadata().get("channel.feishu.fileKey"));
        assertEquals("demo.mp4", message.metadata().get("channel.feishu.fileName"));
        assertTrue(message.metadata().get("attachments").contains("\"type\":\"media\""));
        assertTrue(message.metadata().get("attachments").contains("missing-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("1", message.metadata().get("channel.mediaAttachmentCount"));
        assertEquals("media", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void reportsMissingCredentialsAsIncomplete() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelConnectivityStatus status = adapter.checkConnectivity(channel(Map.of()));

        assertFalse(status.ready());
        assertEquals("incomplete", status.status());
        assertTrue(status.missingKeys().contains("appId/appIdEnv"));
        assertTrue(status.missingKeys().contains("appSecret/appSecretEnv"));
        assertEquals("feishu-http", status.details().get("protocol"));
    }

    @Test
    void reportsMissingStreamCredentialsBeforeStartingSdkClient() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();
        ChannelDefinition channel = channel(Map.of("connectionMode", "long-connection"));

        Exception error = assertThrows(Exception.class, () -> adapter.startStream(channel));

        assertTrue(error.getMessage().contains("appId"));
    }

    private ChannelDefinition channel(Map<String, String> metadata) {
        return new ChannelDefinition("feishu", "飞书", "feishu", true,
                "ask", List.of(), "/api/v1/channels/feishu/inbound", metadata, null, null);
    }

    private P2MessageReceiveV1 streamEvent(String messageType, String messageId, String content) {
        P2MessageReceiveV1 event = new P2MessageReceiveV1();
        Header header = new Header();
        header.setEventId("ev-" + messageId);
        header.setEventType("im.message.receive_v1");
        header.setCreateTime("1700000000000");
        header.setTenantKey("tenant-1");
        event.setHeader(header);
        P2MessageReceiveV1Data data = new P2MessageReceiveV1Data();
        EventMessage sdkMessage = new EventMessage();
        sdkMessage.setChatId("oc-stream");
        sdkMessage.setMessageId(messageId);
        sdkMessage.setCreateTime("1700000000001");
        sdkMessage.setChatType("group");
        sdkMessage.setMessageType(messageType);
        sdkMessage.setContent(content);
        EventSender sender = new EventSender();
        UserId senderId = new UserId();
        senderId.setUserId("ou-stream-user");
        sender.setSenderId(senderId);
        sender.setTenantKey("tenant-1");
        data.setMessage(sdkMessage);
        data.setSender(sender);
        event.setEvent(data);
        return event;
    }

    private String encryptFeishuPayload(String json, String encryptKey) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] iv = "1234567890123456".getBytes(StandardCharsets.UTF_8);
        byte[] plain = pkcs7Pad(json.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private byte[] pkcs7Pad(byte[] input) {
        int padding = 16 - (input.length % 16);
        byte[] result = new byte[input.length + padding];
        System.arraycopy(input, 0, result, 0, input.length);
        for (int i = input.length; i < result.length; i++) {
            result[i] = (byte) padding;
        }
        return result;
    }
}
