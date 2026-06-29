package com.github.clawagent.channel.feishu;

import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelSendResult;
import com.github.clawagent.channel.ChannelStreamHandle;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.spi.ChannelAdapter;

import java.util.Map;

/**
 * 飞书/Lark 入站 Channel SPI 适配器。
 */
public class FeishuChannelAdapter implements ChannelAdapter, ChannelRuntimeAdapter {
    private final FeishuOutboundClient outboundClient;
    private final FeishuStreamClient streamClient;

    public FeishuChannelAdapter() {
        this(null);
    }

    public FeishuChannelAdapter(ChannelRouter channelRouter) {
        this.outboundClient = new FeishuOutboundClient();
        this.streamClient = new FeishuStreamClient(channelRouter);
    }

    @Override
    public String type() {
        return "feishu";
    }

    @Override
    public boolean supports(String channelType) {
        return "feishu".equalsIgnoreCase(channelType) || "lark".equalsIgnoreCase(channelType);
    }

    @Override
    public ChannelInboundMessage adaptInbound(String channelId, Map<String, Object> rawPayload) {
        ChannelInboundPayloadResult result = adaptInbound(null, channelId, rawPayload);
        if (result.hasImmediateResponse()) {
            throw new IllegalArgumentException("飞书 URL challenge 需要直接响应平台，不能转换为 Agent 消息。");
        }
        return result.message();
    }

    @Override
    public boolean detectInbound(Map<String, Object> payload) {
        return FeishuInboundAdapter.detect(payload);
    }

    @Override
    public ChannelInboundPayloadResult adaptInbound(ChannelDefinition channel, String channelId, Map<String, Object> rawPayload) {
        // 当前 ChannelAdapter SPI 只能返回消息，URL challenge 仍由 HTTP 层的 adaptWithResponse 处理。
        ChannelInboundPayloadResult result = FeishuInboundAdapter.adapt(channel, type(), rawPayload);
        if (result.hasImmediateResponse()) {
            return result;
        }
        return ChannelInboundPayloadResult.message(withChannelId(channelId, result.message()));
    }

    @Override
    public ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        return outboundClient.sendMessage(channel, sourceMessage, text);
    }

    @Override
    public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        return outboundClient.checkConnectivity(channel);
    }

    @Override
    public ChannelStreamHandle startStream(ChannelDefinition channel) throws Exception {
        if (metadataValue(channel, "appId", "appIdEnv").isBlank()
                || metadataValue(channel, "appSecret", "appSecretEnv").isBlank()) {
            throw new IllegalArgumentException("飞书长连接需要 appId/appIdEnv 和 appSecret/appSecretEnv。");
        }
        return streamClient.start(channel);
    }

    @Override
    public String streamMode(ChannelDefinition channel) {
        return FeishuStreamClient.MODE;
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
