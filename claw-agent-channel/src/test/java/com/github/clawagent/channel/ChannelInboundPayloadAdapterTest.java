package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.ChannelDefinition;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelInboundPayloadAdapterTest {

    @Test
    void keepsGenericChannelInboundMessageShape() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt(null, Map.of(
                "channelId", "api",
                "externalConversationId", "conv-1",
                "externalUserId", "user-1",
                "messageType", "text",
                "text", "hello",
                "metadata", Map.of("source", "test")
        ));

        assertEquals("api", message.channelId());
        assertEquals("conv-1", message.externalConversationId());
        assertEquals("user-1", message.externalUserId());
        assertEquals("hello", message.text());
        assertEquals("test", message.metadata().get("source"));
    }

    @Test
    void treatsNullPayloadAsGenericInboundMessage() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt(null, null);

        assertEquals("api", message.channelId());
        assertEquals("default", message.externalConversationId());
        assertEquals("external", message.externalUserId());
        assertEquals("text", message.messageType());
        assertEquals("", message.text());
    }

    @Test
    void extractsFeishuTextEvent() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt("feishu", Map.of(
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

        assertEquals("feishu", message.channelId());
        assertEquals("oc-chat", message.externalConversationId());
        assertEquals("ou-user", message.externalUserId());
        assertEquals("text", message.messageType());
        assertEquals("飞书消息", message.text());
        assertEquals("feishu", message.metadata().get("channel.adapter"));
        assertEquals("om-message", message.metadata().get("channel.messageId"));
    }

    @Test
    void returnsFeishuPlainChallengeAndChecksVerificationToken() {
        ChannelDefinition channel = channel("feishu", Map.of("verificationToken", "token-1"));

        ChannelInboundPayloadResult result = ChannelInboundPayloadAdapter.adaptWithResponse(channel, Map.of(
                "challenge", "challenge-1",
                "token", "token-1",
                "type", "url_verification"
        ));

        assertTrue(result.hasImmediateResponse());
        assertEquals(Map.of("challenge", "challenge-1"), result.responseBody());
        assertThrows(IllegalArgumentException.class, () -> ChannelInboundPayloadAdapter.adaptWithResponse(channel, Map.of(
                "challenge", "challenge-1",
                "token", "bad-token",
                "type", "url_verification"
        )));
    }

    @Test
    void decryptsFeishuEncryptedChallenge() throws Exception {
        ChannelDefinition channel = channel("feishu", Map.of("encryptKey", "encrypt-key-1", "verificationToken", "token-1"));
        String encrypted = encryptFeishuPayload("{\"challenge\":\"challenge-2\",\"token\":\"token-1\",\"type\":\"url_verification\"}", "encrypt-key-1");

        ChannelInboundPayloadResult result = ChannelInboundPayloadAdapter.adaptWithResponse(channel, Map.of("encrypt", encrypted));

        assertTrue(result.hasImmediateResponse());
        assertEquals(Map.of("challenge", "challenge-2"), result.responseBody());
    }

    @Test
    void detectsFeishuEventFromGenericInboundPayload() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt(null, Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("open_id", "ou-open")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_type", "text",
                                "content", "{\"text\":\"通用入口飞书消息\"}"
                        )
                )
        ));

        assertEquals("feishu", message.channelId());
        assertEquals("oc-chat", message.externalConversationId());
        assertEquals("ou-open", message.externalUserId());
        assertEquals("通用入口飞书消息", message.text());
    }

    @Test
    void extractsFeishuImageEventAsPlaceholderText() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt("feishu", Map.of(
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
        assertEquals("image", message.metadata().get("channel.platformMessageType"));
        assertEquals("img_v2_abc", message.metadata().get("channel.feishu.imageKey"));
    }

    @Test
    void extractsFeishuFileEventAsPlaceholderText() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt("feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("open_id", "ou-open")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_type", "file",
                                "content", "{\"file_key\":\"file_v2_abc\",\"file_name\":\"需求文档.docx\"}"
                        )
                )
        ));

        assertEquals("file", message.messageType());
        assertEquals("[飞书文件] 需求文档.docx", message.text());
        assertEquals("file_v2_abc", message.metadata().get("channel.feishu.fileKey"));
        assertEquals("需求文档.docx", message.metadata().get("channel.feishu.fileName"));
    }

    @Test
    void extractsDingtalkTextEvent() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt("dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "senderNick", "张三",
                "msgtype", "text",
                "text", Map.of("content", "钉钉消息")
        ));

        assertEquals("dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("staff-1", message.externalUserId());
        assertEquals("text", message.messageType());
        assertEquals("钉钉消息", message.text());
        assertEquals("dingtalk", message.metadata().get("channel.adapter"));
        assertEquals("张三", message.metadata().get("channel.senderNick"));
    }

    @Test
    void verifiesDingtalkSignedInboundEventWhenSecretConfigured() {
        long timestamp = 1700000000000L;
        String secret = "SEC123456";
        ChannelDefinition channel = channel("dingtalk", Map.of("secret", secret));
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adaptWithResponse(channel, Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "text",
                "text", Map.of("content", "已签名钉钉消息"),
                "_query", ChannelInboundPayloadAdapter.dingtalkSignedQuery(secret, timestamp)
        )).message();

        assertEquals("已签名钉钉消息", message.text());
    }

    @Test
    void rejectsDingtalkInboundEventWithInvalidSignature() {
        ChannelDefinition channel = channel("dingtalk", Map.of("secret", "SEC123456"));

        assertThrows(IllegalArgumentException.class, () -> ChannelInboundPayloadAdapter.adaptWithResponse(channel, Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "text",
                "text", Map.of("content", "伪造钉钉消息"),
                "_query", Map.of("timestamp", "1700000000000", "sign", "bad-sign")
        )));
    }

    @Test
    void detectsDingtalkEventFromGenericInboundPayload() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt(null, Map.of(
                "conversationId", "cid-1",
                "senderId", "sender-1",
                "msgtype", "text",
                "text", Map.of("content", "通用入口钉钉消息")
        ));

        assertEquals("dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("sender-1", message.externalUserId());
        assertEquals("通用入口钉钉消息", message.text());
    }

    @Test
    void extractsDingtalkImageEventAsPlaceholderText() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt(null, Map.of(
                "conversationId", "cid-1",
                "senderId", "sender-1",
                "msgtype", "image",
                "image", Map.of("picUrl", "https://example.invalid/a.png")
        ));

        assertEquals("dingtalk", message.channelId());
        assertEquals("image", message.messageType());
        assertEquals("[钉钉图片] https://example.invalid/a.png", message.text());
        assertEquals("image", message.metadata().get("channel.platformMessageType"));
        assertEquals("https://example.invalid/a.png", message.metadata().get("channel.dingtalk.picUrl"));
    }

    @Test
    void extractsDingtalkMarkdownEventAsPlaceholderText() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adapt("dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "markdown",
                "markdown", Map.of("title", "发布提醒", "text", "### 发布提醒")
        ));

        assertEquals("markdown", message.messageType());
        assertEquals("[钉钉 Markdown] 发布提醒", message.text());
        assertEquals("发布提醒", message.metadata().get("channel.dingtalk.markdownTitle"));
    }

    @Test
    void createsDingtalkOfficialRobotSignature() throws Exception {
        long timestamp = 1700000000000L;
        String secret = "SEC123456";
        Map<String, String> signed = ChannelInboundPayloadAdapter.dingtalkSignedQuery(secret, timestamp);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));

        assertEquals(String.valueOf(timestamp), signed.get("timestamp"));
        assertTrue(signed.get("sign").contains("%"));
        assertEquals(java.net.URLEncoder.encode(expected, StandardCharsets.UTF_8), signed.get("sign"));
    }

    private ChannelDefinition channel(String type, Map<String, String> metadata) {
        return new ChannelDefinition(type, type, type, true, "ask", List.of(),
                "/api/v1/channels/" + type + "/inbound", metadata, null, null);
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
