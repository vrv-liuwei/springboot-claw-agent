package com.github.clawagent.channel;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.ChannelInboundResult;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.spi.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Channel 入站路由服务。
 * 它把外部消息转换成 AgentRequest，server 和后续 IM adapter 都应复用这条链路。
 */
public class ChannelRouter {
    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);

    private final AgentRuntime runtime;
    private final ChannelRegistry channelRegistry;
    private final ChannelSessionMapper sessionMapper;
    private final ChannelOutboundClient outboundClient;

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper) {
        this(runtime, channelRegistry, sessionMapper, null);
    }

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper,
                         ChannelOutboundClient outboundClient) {
        this.runtime = runtime;
        this.channelRegistry = channelRegistry;
        this.sessionMapper = sessionMapper;
        this.outboundClient = outboundClient;
    }

    public ChannelInboundResult receive(ChannelInboundMessage message) {
        String channelId = firstNonBlank(message == null ? "" : message.channelId(), "api");
        return receive(channelId, message);
    }

    public ChannelInboundResult receive(String channelId, ChannelInboundMessage message) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        if (!channel.enabled()) {
            throw new IllegalStateException("Channel 未启用：" + channel.id());
        }

        ChannelInboundMessage safeMessage = message == null
                ? new ChannelInboundMessage(null, null, null, null, null, Map.of(), Map.of())
                : message;
        String text = firstNonBlank(safeMessage.text(), "");
        boolean hasAttachments = safeMessage.metadata() != null
                && safeMessage.metadata().get("attachments") != null
                && !safeMessage.metadata().get("attachments").isBlank();
        if (text.isBlank() && !hasAttachments) {
            throw new IllegalArgumentException("Channel 入站消息 input 和 metadata.attachments 不能同时为空");
        }

        String conversationId = firstNonBlank(safeMessage.externalConversationId(), "default");
        String externalUserId = firstNonBlank(safeMessage.externalUserId(), "external");
        String sessionId = sessionMapper.stableSessionId(channel.id(), conversationId);
        Map<String, String> metadata = channelRequestMetadata(channel, safeMessage, conversationId, externalUserId);
        AgentResult result = runtime.submit(new AgentRequest(text, sessionId, channel.id(), externalUserId, metadata));
        if (outboundClient != null) {
            // Channel 已启用时入站和出站使用同一个开关，避免配置出现单向启用但业务不可用。
            ChannelSendResult sendResult = outboundClient.sendTextDetailed(channel, safeMessage, result.answer());
            if (sendResult.sent()) {
                log.info("channel outbound sent channelId={} channelType={} conversationId={} userId={} status={} details={}",
                        channel.id(), channel.type(), conversationId, externalUserId, sendResult.status(), sendResult.details());
            } else {
                log.warn("channel outbound failed channelId={} channelType={} conversationId={} userId={} status={} message={} details={}",
                        channel.id(), channel.type(), conversationId, externalUserId, sendResult.status(), sendResult.message(), sendResult.details());
            }
        }
        return new ChannelInboundResult(channel.id(), sessionId, result.taskId(), result.status().name(), result.answer());
    }

    private Map<String, String> channelRequestMetadata(
            ChannelDefinition channel,
            ChannelInboundMessage message,
            String conversationId,
            String externalUserId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (message.metadata() != null) {
            metadata.putAll(message.metadata());
        }
        // 这些字段是后续审计、记忆 scope、消息回写和策略匹配的最小事实集。
        metadata.put("source", "channel-inbound");
        metadata.put("channel.id", channel.id());
        metadata.put("channel.type", channel.type());
        metadata.put("channel.name", channel.name());
        metadata.put("channel.externalConversationId", conversationId);
        metadata.put("channel.externalUserId", externalUserId);
        metadata.put("channel.messageType", firstNonBlank(message.messageType(), "text"));
        metadata.put("toolPermissionMode", firstNonBlank(channel.approvalMode(), "ask"));
        metadata.put("policy.approval.source", "channel:" + channel.id());
        metadata.put("policy.approval.scope", "channel");
        metadata.put("policy.resolutionOrder", "local>channel>task>agent-isolation>tool-enforcement");
        if (channel.approvedToolIds() != null && !channel.approvedToolIds().isEmpty()) {
            metadata.put("approvedToolIds", String.join(",", channel.approvedToolIds()));
        }
        return metadata;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
