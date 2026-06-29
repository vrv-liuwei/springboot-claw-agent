package com.github.clawagent.server.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelInboundPayloadAdapter;
import com.github.clawagent.channel.ChannelMessageDeduplicator;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.spi.ChannelRegistry;

/**
 * DDIO webhook 回调入口。
 */
@RestController
public class DdioWebhookController {
    private static final String DDIO_WEBHOOK_PATH = "/ddio/message";

    private static final Logger LOGGER = LoggerFactory.getLogger(DdioWebhookController.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelRouter channelRouter;
    private final ChannelAdapterRegistry channelAdapterRegistry;
    private final ChannelMessageDeduplicator messageDeduplicator = new ChannelMessageDeduplicator(Duration.ofMinutes(10));

    public DdioWebhookController(ChannelRegistry channelRegistry, ChannelRouter channelRouter, ChannelAdapterRegistry channelAdapterRegistry) {
        this.channelRegistry = channelRegistry;
        this.channelRouter = channelRouter;
        this.channelAdapterRegistry = channelAdapterRegistry;
    }

    /**
     * DDIO 平台回调验证。
     */
    @GetMapping(DDIO_WEBHOOK_PATH)
    public Map<String, Object> verifyDdioWebhook() {
        return Map.of("code", 0, "msg", true);
    }

    /**
     * DDIO 入站消息。
     */
    @PostMapping(DDIO_WEBHOOK_PATH)
    public Object receiveDdioMessage(
            @RequestBody Map<String, Object> payload,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> query) {
        LOGGER.info("DDIO webhook payload: {}, headers: {}, query: {}", payload, headers, query);
        ChannelDefinition channel = resolveDdioChannel();
        Map<String, Object> enriched = enrichTransport(payload, headers, query);
        String messageKey = ddioMessageKey(channel.id(), payload);
        if (messageDeduplicator.isDuplicate(messageKey)) {
            LOGGER.info("DDIO webhook duplicate ignored messageKey={}", messageKey);
            return ddioAck(true);
        }
        // DDIO 会在回调超时后重试；这里先 ACK，再异步进入 Agent，避免同一 messageID 触发多轮任务。
        CompletableFuture.runAsync(() -> processDdioMessageAsync(channel, enriched, messageKey));
        return ddioAck(false);
    }

    ChannelDefinition resolveDdioChannel() {
        List<ChannelDefinition> ddioChannels = channelRegistry.list().stream()
                .filter(this::isDdioChannel)
                .toList();
        // /ddio/message 是兼容入口：YML 中启用的账号要优先于内置的禁用占位通道。
        return findEnabledExactDdio()
                .or(() -> findEnabledDefault(ddioChannels))
                .or(() -> findFirstEnabled(ddioChannels))
                .or(() -> channelRegistry.find("ddio"))
                .or(() -> ddioChannels.stream().findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：ddio"));
    }

    private Optional<ChannelDefinition> findEnabledExactDdio() {
        return channelRegistry.find("ddio").filter(ChannelDefinition::enabled);
    }

    private Optional<ChannelDefinition> findEnabledDefault(List<ChannelDefinition> channels) {
        return channels.stream()
                .filter(ChannelDefinition::enabled)
                .filter(channel -> "true".equalsIgnoreCase(metadata(channel, "channel.isDefaultAccount")))
                .findFirst();
    }

    private Optional<ChannelDefinition> findFirstEnabled(List<ChannelDefinition> channels) {
        return channels.stream().filter(ChannelDefinition::enabled).findFirst();
    }

    private boolean isDdioChannel(ChannelDefinition channel) {
        return channel != null && "ddio".equalsIgnoreCase(channel.type());
    }

    private String metadata(ChannelDefinition channel, String key) {
        if (channel.metadata() == null) {
            return "";
        }
        return channel.metadata().getOrDefault(key, "");
    }

    private Map<String, Object> enrichTransport(Map<String, Object> payload, Map<String, String> headers, Map<String, String> query) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (payload != null) {
            enriched.putAll(payload);
        }
        enriched.put("_headers", headers == null ? Map.of() : new LinkedHashMap<>(headers));
        enriched.put("_query", query == null ? Map.of() : new LinkedHashMap<>(query));
        return enriched;
    }

    private void processDdioMessageAsync(ChannelDefinition channel, Map<String, Object> enriched, String messageKey) {
        try {
            var adapted = ChannelInboundPayloadAdapter.adaptWithResponse(channelAdapterRegistry, channel, channel.id(), enriched);
            Object result = channelRouter.receive(channel.id(), adapted.message());
            LOGGER.info("DDIO webhook async result messageKey={} result={}", messageKey, result);
        } catch (Exception ex) {
            LOGGER.error("DDIO webhook async processing failed messageKey={}", messageKey, ex);
        }
    }

    private Map<String, Object> ddioAck(boolean duplicate) {
        return Map.of("code", 0, "msg", true, "duplicate", duplicate);
    }

    private String ddioMessageKey(String channelId, Map<String, Object> payload) {
        Map<String, Object> message = mapValue(payload == null ? null : payload.get("message"));
        String messageId = firstNonBlank(
                stringValue(payload, "messageID"),
                stringValue(payload, "messageId"),
                stringValue(payload, "msgId"),
                stringValue(message, "messageID"),
                stringValue(message, "messageId"),
                stringValue(message, "msgId"));
        if (!messageId.isBlank()) {
            return channelId + ":" + messageId;
        }
        // 极少数回调缺少 messageID 时，用核心字段兜底，仍然避免短时间重复推送。
        return channelId + ":fallback:"
                + stringValue(payload, "sendUserID") + ":"
                + stringValue(payload, "receTargetID") + ":"
                + stringValue(payload, "sendTime");
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Map<String, Object> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

}
