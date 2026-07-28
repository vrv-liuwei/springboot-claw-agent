package com.github.clawagent.channel.dingtalk;

import com.dingtalk.open.app.api.message.GenericOpenDingTalkEvent;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelInboundPayloadAdapter;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shade.com.alibaba.fastjson2.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingtalkChannelAdapterTest {

    @Test
    void convertsTextEventAndKeepsConfiguredChannelId() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "senderNick", "张三",
                "msgId", "msg-1",
                "createAt", "1700000000000",
                "conversationType", "2",
                "msgtype", "text",
                "text", Map.of("content", "钉钉消息")
        ));

        assertTrue(adapter.supports("dingtalk"));
        assertEquals("corp-dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("staff-1", message.externalUserId());
        assertEquals("钉钉消息", message.text());
        assertEquals("dingtalk", message.metadata().get("channel.adapter"));
        assertEquals("http", message.metadata().get("channel.eventSource"));
        assertEquals("message", message.metadata().get("channel.eventCategory"));
        assertEquals("张三", message.metadata().get("channel.senderNick"));
        assertEquals("张三", message.metadata().get("channel.senderName"));
        assertEquals("msg-1", message.metadata().get("channel.messageId"));
        assertEquals("msg-1", message.metadata().get("channel.eventId"));
        assertEquals("text", message.metadata().get("channel.eventType"));
        assertEquals("1700000000000", message.metadata().get("channel.messageCreateTime"));
        assertEquals("2", message.metadata().get("channel.conversationType"));
        assertEquals("cid-1", message.metadata().get("channel.conversationId"));
        assertEquals("staff-1", message.metadata().get("channel.externalUserId"));
    }

    @Test
    void autoDetectsNonMessagePlatformEvent() {
        ChannelInboundMessage message = ChannelInboundPayloadAdapter.adaptWithResponse(
                new ChannelAdapterRegistry(List.of(new DingtalkChannelAdapter())),
                null,
                "corp-dingtalk",
                Map.of(
                "eventType", "conversation.member.added",
                "eventId", "dt-event-1",
                "conversationId", "cid-event",
                "operatorStaffId", "staff-operator",
                "createTime", "1700000000005"
        )).message();

        assertEquals("corp-dingtalk", message.channelId());
        assertEquals("cid-event", message.externalConversationId());
        assertEquals("staff-operator", message.externalUserId());
        assertEquals("conversation.member.added", message.messageType());
        assertEquals("[钉钉非文本消息] type=conversation.member.added", message.text());
        assertEquals("member", message.metadata().get("channel.eventCategory"));
        assertEquals("added", message.metadata().get("channel.eventAction"));
        assertEquals("member.added", message.metadata().get("channel.eventSemantic"));
        assertEquals("conversation.member.added", message.metadata().get("channel.eventType"));
        assertEquals("dt-event-1", message.metadata().get("channel.eventId"));
        assertEquals("dt-event-1", message.metadata().get("channel.messageId"));
        assertEquals("cid-event", message.metadata().get("channel.conversationId"));
        assertEquals("staff-operator", message.metadata().get("channel.externalUserId"));
    }

    @Test
    void verifiesSignedInboundEventWhenSecretConfigured() {
        long timestamp = 1700000000000L;
        String secret = "SEC123456";
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();
        ChannelDefinition channel = channel(Map.of("secret", secret));

        ChannelInboundMessage message = adapter.adaptInbound(channel, "dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "text",
                "text", Map.of("content", "已签名钉钉消息"),
                "_query", DingtalkInboundAdapter.signedQuery(secret, timestamp)
        )).message();

        assertEquals("已签名钉钉消息", message.text());
    }

    @Test
    void extractsFileEventAsAttachmentMetadata() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "file",
                "file", Map.of("fileName", "report.pdf", "downloadCode", "download-code-1")
        ));

        assertEquals("file", message.messageType());
        assertEquals("[钉钉文件] report.pdf", message.text());
        assertEquals("report.pdf", message.metadata().get("channel.dingtalk.fileName"));
        assertTrue(message.metadata().get("attachments").contains("download-code-1"));
        assertTrue(message.metadata().get("attachments").contains("missing-access-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("file", message.metadata().get("channel.attachmentTypes"));
        assertEquals("skipped", message.metadata().get("channel.attachmentDownloadStatuses"));
    }

    @Test
    void mapsActionCardAsRenderedAttachment() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-dingtalk", Map.of(
                "conversationId", "cid-card",
                "senderStaffId", "staff-card",
                "msgtype", "actionCard",
                "actionCard", Map.of(
                        "title", "发布审批",
                        "text", "请确认是否发布生产版本",
                        "btns", List.of(Map.of("title", "批准", "actionURL", "https://example.com/approve")))
        ));

        String attachments = message.metadata().get("attachments");
        assertEquals("actionCard", message.messageType());
        assertEquals("[钉钉卡片] 发布审批", message.text());
        assertEquals("发布审批", message.metadata().get("channel.dingtalk.cardTitle"));
        assertTrue(attachments.contains("\"type\":\"card\""));
        assertTrue(attachments.contains("\"renderStatus\":\"rendered\""));
        assertTrue(attachments.contains("\"renderFormat\":\"markdown\""));
        assertTrue(attachments.contains("请确认是否发布生产版本"));
        assertTrue(attachments.contains("批准 -&gt; https://example.com/approve") || attachments.contains("批准 -> https://example.com/approve"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("card", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void downloadsDingtalkFileByDownloadCode(@TempDir Path tempDir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> tokenHeader = new AtomicReference<>("");
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server.createContext("/download", exchange -> {
            tokenHeader.set(exchange.getRequestHeaders().getFirst("x-acs-dingtalk-access-token"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = "{\"downloadUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/file\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext("/file", exchange -> {
            byte[] bytes = "dingtalk-file-content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();
            ChannelDefinition channel = channel(Map.of(
                    "downloadCodeApiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/download",
                    "accessToken", "access-token-1",
                    "robotCode", "robot-code-1",
                    "mediaDownloadDir", tempDir.toString()));

            ChannelInboundMessage message = adapter.adaptInbound(channel, "dingtalk", Map.of(
                    "conversationId", "cid-1",
                    "senderStaffId", "staff-1",
                    "msgtype", "file",
                    "msgId", "msg-file",
                    "file", Map.of("fileName", "report.txt", "downloadCode", "download-code-1")
            )).message();

            assertEquals("access-token-1", tokenHeader.get());
            assertTrue(requestBody.get().contains("download-code-1"));
            assertTrue(requestBody.get().contains("robot-code-1"));
            assertTrue(message.metadata().get("attachments").contains("\"downloadStatus\":\"downloaded\""));
            assertTrue(message.metadata().get("attachments").contains("report.txt"));
            assertTrue(Files.exists(tempDir.resolve("dingtalk").resolve("msg-file").resolve("report.txt")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInboundEventWithInvalidSignature() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();
        ChannelDefinition channel = channel(Map.of("secret", "SEC123456"));

        assertThrows(IllegalArgumentException.class, () -> adapter.adaptInbound(channel, "dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "text",
                "text", Map.of("content", "伪造钉钉消息"),
                "_query", Map.of("timestamp", "1700000000000", "sign", "bad-sign")
        )));
    }

    @Test
    void createsOfficialRobotSignature() throws Exception {
        long timestamp = 1700000000000L;
        String secret = "SEC123456";
        Map<String, String> signed = DingtalkInboundAdapter.signedQuery(secret, timestamp);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8)));

        assertEquals(String.valueOf(timestamp), signed.get("timestamp"));
        assertTrue(signed.get("sign").contains("%"));
        assertEquals(java.net.URLEncoder.encode(expected, StandardCharsets.UTF_8), signed.get("sign"));
    }

    @Test
    void checksCustomRobotConnectivityWithoutSendingMessage() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();
        ChannelDefinition channel = channel(Map.of(
                "webhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=token",
                "secret", "secret-1"));

        ChannelConnectivityStatus status = adapter.checkConnectivity(channel);

        assertTrue(status.ready());
        assertFalse(status.probedRemote());
        assertEquals("ready", status.status());
        assertEquals("dingtalk-custom-robot", status.details().get("protocol"));
        assertEquals("true", status.details().get("signed"));
    }

    @Test
    void checksStreamConnectivityWithoutWebhookUrl() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();
        ChannelDefinition channel = channel(Map.of("connectionMode", "stream", "clientId", "client-1", "clientSecret", "secret-1"));

        ChannelConnectivityStatus status = adapter.checkConnectivity(channel);

        assertTrue(status.ready());
        assertFalse(status.probedRemote());
        assertEquals("dingtalk-stream", status.details().get("protocol"));
    }

    @Test
    void reportsMissingStreamCredentialsBeforeStartingSdkClient() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();

        Exception error = assertThrows(Exception.class, () -> adapter.startStream(channel(Map.of("connectionMode", "stream"))));

        assertTrue(error.getMessage().contains("clientId"));
    }

    @Test
    void normalizesStreamTextPayload() {
        DingtalkStreamClient client = new DingtalkStreamClient(null);
        ChannelDefinition channel = channel(Map.of());
        GenericOpenDingTalkEvent event = new GenericOpenDingTalkEvent();
        event.setEventId("event-1");
        event.setEventType("chat_message");
        event.setEventCorpId("corp-1");
        event.setEventUnifiedAppId("app-1");
        JSONObject data = new JSONObject();
        JSONObject text = new JSONObject();
        text.put("content", "钉钉 Stream 消息");
        data.put("conversationId", "cid-1");
        data.put("senderStaffId", "staff-1");
        data.put("senderNick", "李四");
        data.put("msgtype", "text");
        data.put("text", text);
        event.setData(data);

        ChannelInboundMessage message = client.toInboundMessage(channel, event);

        assertEquals("dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("staff-1", message.externalUserId());
        assertEquals("text", message.messageType());
        assertEquals("钉钉 Stream 消息", message.text());
        assertEquals("dingtalk-stream", message.metadata().get("channel.adapter"));
        assertEquals("stream", message.metadata().get("channel.eventSource"));
        assertEquals("message", message.metadata().get("channel.eventCategory"));
        assertEquals("李四", message.metadata().get("channel.senderNick"));
        assertEquals("李四", message.metadata().get("channel.senderName"));
        assertEquals("chat_message", message.metadata().get("channel.eventType"));
        assertEquals("event-1", message.metadata().get("channel.messageId"));
        assertEquals("event-1", message.metadata().get("channel.eventId"));
        assertEquals("cid-1", message.metadata().get("channel.conversationId"));
        assertEquals("staff-1", message.metadata().get("channel.externalUserId"));
        assertEquals("corp-1", message.metadata().get("channel.corpId"));
        assertEquals("app-1", message.metadata().get("channel.appId"));
        assertEquals("event-1", message.rawPayload().get("eventId"));
    }

    @Test
    void normalizesStreamFilePayloadAsAttachmentMetadata() {
        DingtalkStreamClient client = new DingtalkStreamClient(null);
        GenericOpenDingTalkEvent event = new GenericOpenDingTalkEvent();
        event.setEventId("event-file-1");
        event.setEventType("chat_message");
        JSONObject data = new JSONObject();
        JSONObject file = new JSONObject();
        file.put("fileName", "设计稿.zip");
        file.put("downloadCode", "download-code-1");
        data.put("conversationId", "cid-file");
        data.put("senderStaffId", "staff-file");
        data.put("msgId", "msg-file-1");
        data.put("createAt", "1700000000002");
        data.put("conversationType", "2");
        data.put("msgtype", "file");
        data.put("file", file);
        event.setData(data);

        ChannelInboundMessage message = client.toInboundMessage(channel(Map.of()), event);

        assertEquals("file", message.messageType());
        assertEquals("msg-file-1", message.metadata().get("channel.messageId"));
        assertEquals("event-file-1", message.metadata().get("channel.eventId"));
        assertEquals("1700000000002", message.metadata().get("channel.messageCreateTime"));
        assertEquals("2", message.metadata().get("channel.conversationType"));
        assertEquals("设计稿.zip", message.metadata().get("channel.dingtalk.fileName"));
        assertEquals("download-code-1", message.metadata().get("channel.dingtalk.downloadCode"));
        assertTrue(message.metadata().get("attachments").contains("download-code-1"));
        assertTrue(message.metadata().get("attachments").contains("missing-access-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("file", message.metadata().get("channel.attachmentTypes"));
    }

    @Test
    void normalizesBotFileMessageAsAttachmentMetadata() {
        DingtalkStreamClient client = new DingtalkStreamClient(null);
        ChatbotMessage botMessage = new ChatbotMessage();
        botMessage.setConversationId("cid-bot");
        botMessage.setSenderStaffId("staff-bot");
        botMessage.setMsgId("msg-bot-file");
        botMessage.setCreateAt(1700000000003L);
        botMessage.setConversationType("1");
        botMessage.setMsgtype("file");
        MessageContent content = new MessageContent();
        content.setFileName("周报.docx");
        content.setDownloadCode("bot-download-code");
        botMessage.setContent(content);

        ChannelInboundMessage message = client.toInboundMessage(channel(Map.of()), botMessage);

        assertEquals("file", message.messageType());
        assertEquals("周报.docx", message.text());
        assertEquals("msg-bot-file", message.metadata().get("channel.messageId"));
        assertEquals("bot", message.metadata().get("channel.eventSource"));
        assertEquals("message", message.metadata().get("channel.eventCategory"));
        assertEquals("msg-bot-file", message.metadata().get("channel.eventId"));
        assertEquals("1700000000003", message.metadata().get("channel.messageCreateTime"));
        assertEquals("1", message.metadata().get("channel.conversationType"));
        assertEquals("cid-bot", message.metadata().get("channel.conversationId"));
        assertEquals("staff-bot", message.metadata().get("channel.externalUserId"));
        assertTrue(message.metadata().get("attachments").contains("bot-download-code"));
        assertTrue(message.metadata().get("attachments").contains("missing-access-token"));
        assertEquals("1", message.metadata().get("channel.attachmentCount"));
        assertEquals("file", message.metadata().get("channel.attachmentTypes"));
    }

    private ChannelDefinition channel(Map<String, String> metadata) {
        return new ChannelDefinition("dingtalk", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk/inbound", metadata, null, null);
    }
}
