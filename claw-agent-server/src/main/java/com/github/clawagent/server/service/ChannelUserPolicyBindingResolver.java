package com.github.clawagent.server.service;

import com.github.clawagent.channel.ChannelUserBindingResolver;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.server.dto.ChannelUserBindingView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server 侧 Channel 用户绑定解析器。
 * 将飞书/钉钉/DDIO 的外部用户映射为本地用户，再复用现有 TaskPolicyEnrichmentService 合并权限。
 */
public class ChannelUserPolicyBindingResolver implements ChannelUserBindingResolver {
    private final ChannelUserBindingService bindingService;
    private final TaskPolicyEnrichmentService policyEnrichmentService;

    public ChannelUserPolicyBindingResolver(ChannelUserBindingService bindingService,
                                            TaskPolicyEnrichmentService policyEnrichmentService) {
        this.bindingService = bindingService;
        this.policyEnrichmentService = policyEnrichmentService;
    }

    @Override
    public Map<String, String> resolve(ChannelDefinition channel, ChannelInboundMessage message, Map<String, String> metadata) {
        Map<String, String> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        String channelId = firstNonBlank(channel == null ? null : channel.id(), message == null ? null : message.channelId());
        String externalUserId = firstNonBlank(message == null ? null : message.externalUserId(),
                merged.get("channel.externalUserId"));
        bindingService.findActive(channelId, externalUserId).ifPresent(binding -> applyBinding(merged, binding));
        String localUserId = firstNonBlank(merged.get("localUserId"), merged.get("user.id"));
        return policyEnrichmentService.resolve(channelId, localUserId, merged).effectiveMetadata();
    }

    private void applyBinding(Map<String, String> metadata, ChannelUserBindingView binding) {
        metadata.put("channel.userBindingId", binding.id());
        metadata.put("channel.boundExternalUserId", binding.externalUserId());
        metadata.put("channel.boundExternalUsername", firstNonBlank(binding.externalUsername(), binding.externalUserId()));
        metadata.put("channel.boundLocalUserId", binding.localUserId());
        metadata.put("channel.boundLocalUsername", firstNonBlank(binding.localUsername(), binding.localUserId()));
        // localUserId/user.id 是现有任务入口识别本地用户策略的兼容字段。
        metadata.put("localUserId", binding.localUserId());
        metadata.put("user.id", binding.localUserId());
        metadata.put("user.username", firstNonBlank(binding.localUsername(), binding.localUserId()));
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second.trim()) : first.trim();
    }
}
