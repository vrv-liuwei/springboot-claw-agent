package com.github.clawagent.channel.dingtalk;

import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelSendResult;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.spi.ChannelAdapter;

import java.util.Map;

/**
 * 钉钉入站 Channel SPI 适配器。
 */
public class DingtalkChannelAdapter implements ChannelAdapter, ChannelRuntimeAdapter {
    private final DingtalkOutboundClient outboundClient;
    private final DingtalkStreamClient streamClient;

    public DingtalkChannelAdapter() {
        this(null);
    }

    public DingtalkChannelAdapter(ChannelRouter channelRouter) {
        this.outboundClient = new DingtalkOutboundClient();
        this.streamClient = new DingtalkStreamClient(channelRouter);
    }

    @Override
    public String type() {
        return "dingtalk";
    }

    @Override
    public boolean supports(String channelType) {
        return ChannelRuntimeAdapter.super.supports(channelType);
    }

    @Override
    public ChannelInboundMessage adaptInbound(String channelId, Map<String, Object> rawPayload) {
        ChannelInboundMessage message = DingtalkInboundAdapter.adapt(null, type(), rawPayload);
        return withChannelId(channelId, message);
    }

    @Override
    public boolean detectInbound(Map<String, Object> payload) {
        return DingtalkInboundAdapter.detect(payload);
    }

    @Override
    public ChannelInboundPayloadResult adaptInbound(ChannelDefinition channel, String channelId, Map<String, Object> rawPayload) {
        ChannelInboundMessage message = DingtalkInboundAdapter.adapt(channel, type(), rawPayload);
        return ChannelInboundPayloadResult.message(withChannelId(channelId, message));
    }

    @Override
    public ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        return outboundClient.sendText(channel, sourceMessage, text);
    }

    @Override
    public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        return outboundClient.checkConnectivity(channel);
    }

    @Override
    public ChannelStreamHandle startStream(ChannelDefinition channel) throws Exception {
        String clientId = firstNonBlank(
                metadataValue(channel, "clientId", "clientIdEnv"),
                metadataValue(channel, "appKey", "appKeyEnv"),
                metadataValue(channel, "appId", "appIdEnv"));
        String clientSecret = firstNonBlank(
                metadataValue(channel, "clientSecret", "clientSecretEnv"),
                metadataValue(channel, "appSecret", "appSecretEnv"));
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalArgumentException("钉钉 Stream 需要 clientId/clientSecret；兼容 appKey/appSecret 或 appId/appSecret。");
        }
        return streamClient.start(channel);
    }

    @Override
    public String streamMode(ChannelDefinition channel) {
        return DingtalkStreamClient.MODE;
    }

    private ChannelInboundMessage withChannelId(String channelId, ChannelInboundMessage message) {
        if (message == null || channelId == null || channelId.isBlank() || channelId.equals(message.channelId())) {
            return message;
        }
        return new ChannelInboundMessage(
                channelId,
                message.externalConversationId(),
                message.externalUserId(),
                message.messageType(),
                message.text(),
                message.metadata(),
                message.rawPayload());
    }
}
