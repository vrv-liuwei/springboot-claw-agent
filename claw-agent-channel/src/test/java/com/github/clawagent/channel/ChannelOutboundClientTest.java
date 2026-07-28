package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
