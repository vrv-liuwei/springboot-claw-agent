package com.github.clawagent.channel;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.ChannelInboundResult;
import com.github.clawagent.intent.IntentRequest;
import com.github.clawagent.intent.IntentRouteResult;
import com.github.clawagent.intent.IntentRoutingService;
import com.github.clawagent.intent.PendingActionResult;
import com.github.clawagent.intent.PendingActionService;
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
    private final IntentRoutingService intentRoutingService;
    private final PendingActionService pendingActionService;
    private final ChannelUserBindingResolver userBindingResolver;

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper) {
        this(runtime, channelRegistry, sessionMapper, null);
    }

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper,
                         ChannelOutboundClient outboundClient) {
        this(runtime, channelRegistry, sessionMapper, outboundClient, null, null);
    }

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper,
                         ChannelOutboundClient outboundClient, IntentRoutingService intentRoutingService,
                         PendingActionService pendingActionService) {
        this(runtime, channelRegistry, sessionMapper, outboundClient, intentRoutingService, pendingActionService,
                ChannelUserBindingResolver.none());
    }

    public ChannelRouter(AgentRuntime runtime, ChannelRegistry channelRegistry, ChannelSessionMapper sessionMapper,
                         ChannelOutboundClient outboundClient, IntentRoutingService intentRoutingService,
                         PendingActionService pendingActionService,
                         ChannelUserBindingResolver userBindingResolver) {
        this.runtime = runtime;
        this.channelRegistry = channelRegistry;
        this.sessionMapper = sessionMapper;
        this.outboundClient = outboundClient;
        this.intentRoutingService = intentRoutingService;
        this.pendingActionService = pendingActionService;
        this.userBindingResolver = userBindingResolver == null ? ChannelUserBindingResolver.none() : userBindingResolver;
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
        Map<String, String> resolvedMetadata = userBindingResolver.resolve(channel, safeMessage, metadata);
        metadata = new LinkedHashMap<>(resolvedMetadata == null ? metadata : resolvedMetadata);
        // IM 通道没有命令行交互，先把“确认执行/取消执行”从普通文本中截获，避免确认语句再次进入 LLM。
        PendingActionResult pendingResult = pendingActionService == null
                ? PendingActionResult.none()
                : pendingActionService.handleUserInput(sessionId, channel.id(), externalUserId, text);
        if (pendingResult.handled()) {
            sendOutbound(channel, safeMessage, conversationId, externalUserId, pendingResult.answer());
            return new ChannelInboundResult(channel.id(), sessionId, pendingResult.action() == null ? "" : pendingResult.action().actionId(), "COMPLETED", pendingResult.answer());
        }
        if (intentRoutingService != null) {
            // 系统固定流程优先走意图路由；只有未命中或需要模型补充上下文时才进入 AgentRuntime。
            IntentRouteResult route = intentRoutingService.route(new IntentRequest(text, sessionId, channel.id(), externalUserId, metadata));
            if (route.handled()) {
                sendOutbound(channel, safeMessage, conversationId, externalUserId, route.answer());
                return new ChannelInboundResult(channel.id(), sessionId, route.intentId(), "COMPLETED", route.answer());
            }
            if (route.passToModel()) {
                // 文档/知识库类意图只补充 metadata，后续由 Runtime 拦截器和 KnowledgeService 完成上下文增强。
                metadata = new LinkedHashMap<>(metadata);
                metadata.putAll(route.metadata());
            }
        }
        // 普通对话和需要模型回答的意图统一从这里进入主 Agent 执行链路。
        AgentResult result = runtime.submit(new AgentRequest(text, sessionId, channel.id(), externalUserId, metadata));
        sendOutbound(channel, safeMessage, conversationId, externalUserId, result.answer());
        return new ChannelInboundResult(channel.id(), sessionId, result.taskId(), result.status().name(), result.answer());
    }

    private void sendOutbound(ChannelDefinition channel, ChannelInboundMessage safeMessage, String conversationId, String externalUserId, String answer) {
        if (outboundClient != null) {
            // Channel 已启用时入站和出站使用同一个开关，避免配置出现单向启用但业务不可用。
            ChannelSendResult sendResult = outboundClient.sendTextDetailed(channel, safeMessage, answer);
            if (sendResult.sent()) {
                log.info("channel outbound sent channelId={} channelType={} conversationId={} userId={} status={} details={}",
                        channel.id(), channel.type(), conversationId, externalUserId, sendResult.status(), sendResult.details());
            } else {
                log.warn("channel outbound failed channelId={} channelType={} conversationId={} userId={} status={} message={} details={}",
                        channel.id(), channel.type(), conversationId, externalUserId, sendResult.status(), sendResult.message(), sendResult.details());
            }
        }
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
