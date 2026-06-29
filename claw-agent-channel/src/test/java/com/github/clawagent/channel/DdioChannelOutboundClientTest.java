package com.github.clawagent.channel;

import com.github.clawagent.channel.ddio.DdioChannelAdapter;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdioChannelOutboundClientTest {

    @Test
    void rejectsDdioSendWithoutCredentials() {
        ChannelOutboundClient outbound = ddioOnlyClient();
        ChannelDefinition channel = ddioChannel(Map.of());
        ChannelInboundMessage source = new ChannelInboundMessage("ddio", "target-1", "user-1", "text",
                "你好，clawagent", Map.of(), Map.of());

        ChannelSendResult result = outbound.sendTextDetailed(channel, source, "回复：收到");

        assertFalse(result.sent());
        assertEquals("failed", result.status());
        assertEquals("ddio-http", result.details().get("protocol"));
        assertEquals("missing-credential", result.details().get("reason"));
    }

    @Test
    void rejectsDdioSendWithoutTarget() {
        ChannelOutboundClient outbound = ddioOnlyClient();
        ChannelDefinition channel = ddioChannel(Map.of("appId", "ddio-app-id", "appSecret", "ddio-secret"));
        ChannelInboundMessage source = new ChannelInboundMessage("ddio", "", "", "text",
                "你好，clawagent", Map.of(), Map.of());

        ChannelSendResult result = outbound.sendTextDetailed(channel, source, "回复：收到");

        assertFalse(result.sent());
        assertEquals("failed", result.status());
        assertEquals("ddio-http", result.details().get("protocol"));
        assertEquals("missing-target-id", result.details().get("reason"));
    }

    @Test
    void reportsDdioConnectivityMissingCredentialsAsIncomplete() {
        ChannelOutboundClient outbound = ddioOnlyClient();

        ChannelConnectivityStatus status = outbound.checkConnectivity(ddioChannel(Map.of()));

        assertFalse(status.ready());
        assertEquals("incomplete", status.status());
        assertTrue(status.missingKeys().contains("appId/appIdEnv"));
        assertTrue(status.missingKeys().contains("appSecret/appSecretEnv"));
        assertEquals("ddio-http", status.details().get("protocol"));
    }

    private ChannelOutboundClient ddioOnlyClient() {
        return new ChannelOutboundClient(new ChannelAdapterRegistry(List.of(new DdioChannelAdapter())));
    }

    private ChannelDefinition ddioChannel(Map<String, String> metadata) {
        return new ChannelDefinition("ddio", "DDIO", "ddio", true,
                "ask", List.of(), "/ddio/message", metadata, null, null);
    }
}
