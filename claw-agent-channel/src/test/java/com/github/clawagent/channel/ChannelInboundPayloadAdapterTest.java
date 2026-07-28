package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
