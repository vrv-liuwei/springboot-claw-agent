package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelOutboundClientTest {

    @Test
    void rejectsInvalidSendRequest() {
        ChannelOutboundClient client = new ChannelOutboundClient();

        ChannelSendResult result = client.sendTextDetailed(null, null, "");

        assertFalse(result.sent());
        assertEquals("failed", result.status());
        assertEquals("invalid-request", result.details().get("reason"));
    }

    @Test
    void returnsUnsupportedForUnknownChannelType() {
        ChannelOutboundClient client = new ChannelOutboundClient();
        ChannelDefinition channel = new ChannelDefinition("custom", "自定义", "custom", true,
                "ask", List.of(), "/custom/inbound", Map.of(), null, null);
        ChannelInboundMessage source = new ChannelInboundMessage("custom", "cid-1", "user-1", "text", "ping",
                Map.of(), Map.of());

        ChannelSendResult result = client.sendTextDetailed(channel, source, "answer");

        assertFalse(result.sent());
        assertEquals("unsupported", result.status());
        assertEquals("custom", result.details().get("channelType"));
    }

    @Test
    void checksDingtalkConnectivityWithoutSendingMessage() {
        ChannelOutboundClient client = new ChannelOutboundClient();
        ChannelDefinition channel = new ChannelDefinition("dingtalk", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk/inbound",
                Map.of("webhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=token",
                        "secret", "secret-1"), null, null);

        ChannelConnectivityStatus status = client.checkConnectivity(channel);

        assertTrue(status.ready());
        assertFalse(status.probedRemote());
        assertEquals("ready", status.status());
        assertEquals("dingtalk-custom-robot", status.details().get("protocol"));
        assertEquals("true", status.details().get("signed"));
    }

    @Test
    void checksDingtalkStreamConnectivityWithoutWebhookUrl() {
        ChannelOutboundClient client = new ChannelOutboundClient();
        ChannelDefinition channel = new ChannelDefinition("dingtalk-main", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk-main/inbound",
                Map.of("connectionMode", "stream", "clientId", "client-1", "clientSecret", "secret-1"), null, null);

        ChannelConnectivityStatus status = client.checkConnectivity(channel);

        assertTrue(status.ready());
        assertFalse(status.probedRemote());
        assertEquals("dingtalk-stream", status.details().get("protocol"));
    }

    @Test
    void reportsFeishuMissingCredentialsAsIncomplete() {
        ChannelOutboundClient client = new ChannelOutboundClient();
        ChannelDefinition channel = new ChannelDefinition("feishu", "飞书", "feishu", true,
                "ask", List.of(), "/api/v1/channels/feishu/inbound", Map.of(), null, null);

        ChannelConnectivityStatus status = client.checkConnectivity(channel);

        assertFalse(status.ready());
        assertEquals("incomplete", status.status());
        assertTrue(status.missingKeys().contains("appId/appIdEnv"));
        assertTrue(status.missingKeys().contains("appSecret/appSecretEnv"));
        assertEquals("feishu-http", status.details().get("protocol"));
    }
}
