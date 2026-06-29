package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

public class JarLoadedChannelAdapter implements ChannelRuntimeAdapter {
    @Override
    public String type() {
        return "jar-workchat";
    }

    @Override
    public ChannelInboundPayloadResult adaptInbound(ChannelDefinition channel, String channelId, Map<String, Object> rawPayload) {
        return ChannelInboundPayloadResult.message(new ChannelInboundMessage(
                channelId,
                "jar-room",
                "jar-user",
                "text",
                "loaded-from-jar",
                Map.of("channel.adapter", type()),
                rawPayload == null ? Map.of() : rawPayload));
    }

    @Override
    public ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        return ChannelSendResult.sent("jar adapter sent", Map.of("adapter", type()));
    }
}
