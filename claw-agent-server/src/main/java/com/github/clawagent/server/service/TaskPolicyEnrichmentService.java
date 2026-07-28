package com.github.clawagent.server.service;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.dto.DeviceView;
import com.github.clawagent.server.dto.LocalUserView;
import com.github.clawagent.server.dto.PolicyResolveLayerView;
import com.github.clawagent.server.dto.PolicyResolveView;
import com.github.clawagent.spi.ChannelRegistry;
import com.github.clawagent.spring.ClawAgentProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 任务策略合并服务。
 * 将 local、channel、user、device、agent isolation 等维度收口成 Runtime 可识别的 metadata。
 */
public class TaskPolicyEnrichmentService {
    private static final String RESOLUTION_ORDER = "local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement";
    private static final List<String> MODE_ORDER = List.of("full", "full-access", "auto", "custom", "ask", "read-only");

    private final LocalUserService localUserService;
    private final DeviceRegistryService deviceRegistryService;
    private final ChannelRegistry channelRegistry;
    private final ClawAgentProperties properties;
    private final ServerAuthProperties authProperties;

    public TaskPolicyEnrichmentService(LocalUserService localUserService, DeviceRegistryService deviceRegistryService) {
        this(localUserService, deviceRegistryService, null, null, null);
    }

    public TaskPolicyEnrichmentService(LocalUserService localUserService,
                                       DeviceRegistryService deviceRegistryService,
                                       ChannelRegistry channelRegistry) {
        this(localUserService, deviceRegistryService, channelRegistry, null, null);
    }

    public TaskPolicyEnrichmentService(LocalUserService localUserService,
                                       DeviceRegistryService deviceRegistryService,
                                       ChannelRegistry channelRegistry,
                                       ClawAgentProperties properties) {
        this(localUserService, deviceRegistryService, channelRegistry, properties, null);
    }

    public TaskPolicyEnrichmentService(LocalUserService localUserService,
                                       DeviceRegistryService deviceRegistryService,
                                       ChannelRegistry channelRegistry,
                                       ClawAgentProperties properties,
                                       ServerAuthProperties authProperties) {
        this.localUserService = localUserService;
        this.deviceRegistryService = deviceRegistryService;
        this.channelRegistry = channelRegistry;
        this.properties = properties;
        this.authProperties = authProperties;
    }

    public Map<String, String> enrich(String channelId, String userId, Map<String, String> metadata) {
        return resolve(channelId, userId, metadata).effectiveMetadata();
    }

    public PolicyResolveView resolve(String channelId, String userId, Map<String, String> metadata) {
        Map<String, String> result = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        List<PolicyLayer> layers = new ArrayList<>();

        collectChannelLayer(channelId, result).ifPresent(layers::add);
        collectExistingLayer(result).ifPresent(layers::add);
        collectUserLayer(userId, result).ifPresent(layers::add);
        collectApiTokenLayer(result).ifPresent(layers::add);
        collectDeviceLayer(result).ifPresent(layers::add);
        collectAgentRoleLayer(result).ifPresent(layers::add);
        collectAgentLayer(result).ifPresent(layers::add);

        PolicyLayer effective = null;
        if (!layers.isEmpty()) {
            effective = merge(layers);
            applyEffectivePolicy(result, effective);
        }
        if (channelId != null && !channelId.isBlank()) {
            result.putIfAbsent("channel.id", channelId.trim());
        }
        return toResolveView(channelId, userId, result, layers, effective);
    }

    private void applyEffectivePolicy(Map<String, String> result, PolicyLayer effective) {
        result.put("toolPermissionMode", effective.mode());
        result.put("policy.approval.source", effective.source());
        result.put("policy.approval.scope", effective.scope());
        result.put("policy.resolutionOrder", RESOLUTION_ORDER);
        result.put("policy.overrideReason", effective.reason());
        if ("read-only".equals(effective.mode())) {
            // 只读隔离下白名单没有放行意义，清掉可以避免页面和审计误判为“只读但仍允许高危工具”。
            result.remove("approvedToolIds");
        } else if (!effective.approvedToolIds().isEmpty()) {
            result.put("approvedToolIds", String.join(",", sortedTools(effective.approvedToolIds())));
        } else {
            result.remove("approvedToolIds");
        }
        if ("read-only".equals(effective.mode())) {
            // read-only 通过现有 Guard 的 agent.isolation 规则实现，避免再复制一套拦截逻辑。
            result.put("agent.isolation", "read-only");
        }
    }

    private PolicyResolveView toResolveView(String channelId, String userId, Map<String, String> metadata,
                                            List<PolicyLayer> layers, PolicyLayer effective) {
        List<String> approved = effective == null ? List.of() : sortedTools(effective.approvedToolIds());
        List<PolicyResolveLayerView> layerViews = layers.stream()
                .map(layer -> new PolicyResolveLayerView(
                        orderForScope(layer.scope()),
                        layer.source(),
                        layer.scope(),
                        layer.mode(),
                        sortedTools(layer.approvedToolIds()),
                        layer.reason(),
                        isEffectiveLayer(layer, effective)))
                .sorted(Comparator.comparingInt(PolicyResolveLayerView::order))
                .toList();
        return new PolicyResolveView(
                channelId == null ? "" : channelId.trim(),
                userId == null ? "" : userId.trim(),
                effective == null ? "" : effective.mode(),
                effective == null ? "" : effective.source(),
                effective == null ? "" : effective.scope(),
                effective == null ? "未命中用户、Token、设备或 Agent 维度策略，沿用输入 metadata。" : effective.reason(),
                approved,
                layerViews,
                new LinkedHashMap<>(metadata));
    }

    private boolean isEffectiveLayer(PolicyLayer layer, PolicyLayer effective) {
        return effective != null
                && layer.source().equals(effective.source())
                && layer.scope().equals(effective.scope())
                && layer.mode().equals(effective.mode());
    }

    private int orderForScope(String scope) {
        return switch (scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT)) {
            case "channel" -> 20;
            case "user" -> 30;
            case "api-token" -> 40;
            case "device" -> 50;
            case "task" -> 60;
            case "agent" -> 70;
            default -> 90;
        };
    }

    private List<String> sortedTools(Set<String> toolIds) {
        return toolIds == null || toolIds.isEmpty() ? List.of() : toolIds.stream().sorted().toList();
    }

    private Optional<PolicyLayer> collectChannelLayer(String channelId, Map<String, String> metadata) {
        if (channelRegistry == null || hasExplicitChannelPolicy(metadata)) {
            return Optional.empty();
        }
        String lookup = firstNonBlank(metadata.get("channel.id"),
                firstNonBlank(metadata.get("channelId"), channelId));
        if (lookup.isBlank()) {
            return Optional.empty();
        }
        Optional<ChannelDefinition> channel = channelRegistry.find(lookup);
        if (channel.isEmpty() || !channel.get().enabled()) {
            return Optional.empty();
        }
        String mode = channel.get().approvalMode();
        Set<String> approved = normalizeToolIds(new LinkedHashSet<>(channel.get().approvedToolIds() == null
                ? List.of()
                : channel.get().approvedToolIds()));
        if ((mode == null || mode.isBlank()) && approved.isEmpty()) {
            return Optional.empty();
        }
        // 普通 Web/API 任务只传 channelId 时，也要复用 Channel 配置里的审批策略，不能只依赖入口提前写 metadata。
        return Optional.of(new PolicyLayer(normalizeMode(mode), approved,
                "channel:" + channel.get().id(), "channel", "Channel 配置策略"));
    }

    private boolean hasExplicitChannelPolicy(Map<String, String> metadata) {
        String mode = firstNonBlank(metadata.get("toolPermissionMode"), metadata.get("approvalMode"));
        Set<String> approved = parseToolIds(metadata.get("approvedToolIds"));
        if (mode.isBlank() && approved.isEmpty()) {
            return false;
        }
        String source = firstNonBlank(metadata.get("policy.approval.source"), inferExistingSource(metadata));
        String scope = firstNonBlank(metadata.get("policy.approval.scope"), inferScope(source));
        return "channel".equalsIgnoreCase(scope) || source.toLowerCase(Locale.ROOT).startsWith("channel:");
    }

    private Optional<PolicyLayer> collectExistingLayer(Map<String, String> metadata) {
        String mode = firstNonBlank(metadata.get("toolPermissionMode"), metadata.get("approvalMode"));
        Set<String> approved = parseToolIds(metadata.get("approvedToolIds"));
        if (mode.isBlank() && approved.isEmpty()) {
            return Optional.empty();
        }
        String source = firstNonBlank(metadata.get("policy.approval.source"), inferExistingSource(metadata));
        String scope = firstNonBlank(metadata.get("policy.approval.scope"), inferScope(source));
        return Optional.of(new PolicyLayer(normalizeMode(mode), approved, source, scope, "现有任务策略"));
    }

    private Optional<PolicyLayer> collectUserLayer(String userId, Map<String, String> metadata) {
        String lookup = firstNonBlank(metadata.get("localUserId"),
                firstNonBlank(metadata.get("user.id"),
                        firstNonBlank(metadata.get("apiToken.ownerUserId"),
                                firstNonBlank(userId, lookupBoundUserId(metadata)))));
        Optional<LocalUserView> user = localUserService.findActive(lookup);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> userMetadata = user.get().metadata() == null ? Map.of() : user.get().metadata();
        ServerAuthProperties.UserRolePolicy rolePolicy = findUserRolePolicy(user.get().role());
        String userMode = firstNonBlank(userMetadata.get("toolPermissionMode"), userMetadata.get("permissionMode"));
        String roleMode = rolePolicyMode(rolePolicy);
        String mode = firstNonBlank(userMode, firstNonBlank(roleMode, defaultModeForRole(user.get().role())));
        Set<String> approved = parseToolIds(firstNonBlank(userMetadata.get("approvedToolIds"), userMetadata.get("toolIds")));
        if (approved.isEmpty() && !hasUserPermissionMetadata(userMetadata) && rolePolicy != null) {
            // 用户没有显式工具白名单时，才继承角色模板；用户 metadata 永远优先于角色默认值。
            approved = normalizeToolIds(new LinkedHashSet<>(rolePolicy.getApprovedToolIds() == null
                    ? List.of()
                    : rolePolicy.getApprovedToolIds()));
        }
        if (mode.isBlank() && approved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyLayer(normalizeMode(mode), approved,
                "user:" + user.get().id(), "user", userPolicyReason(user.get().role(), userMetadata, rolePolicy)));
    }

    private String defaultModeForRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        // viewer 是只读身份，默认收紧到 read-only；其它角色不隐式提权，仍以显式权限字段为准。
        return "viewer".equals(normalized) ? "read-only" : "";
    }

    private String userPolicyReason(String role, Map<String, String> userMetadata,
                                    ServerAuthProperties.UserRolePolicy rolePolicy) {
        if (hasUserPermissionMetadata(userMetadata)) {
            return "本地用户策略";
        }
        if (rolePolicy != null) {
            return "本地用户角色配置策略：" + firstNonBlank(role, "unknown");
        }
        return "本地用户角色策略：" + firstNonBlank(role, "unknown");
    }

    private boolean hasUserPermissionMetadata(Map<String, String> userMetadata) {
        return userMetadata.containsKey("permissionMode") || userMetadata.containsKey("toolPermissionMode")
                || userMetadata.containsKey("approvedToolIds") || userMetadata.containsKey("toolIds");
    }

    private String rolePolicyMode(ServerAuthProperties.UserRolePolicy rolePolicy) {
        return rolePolicy == null ? "" : firstNonBlank(rolePolicy.getPermissionMode(), rolePolicy.getApprovalMode());
    }

    private ServerAuthProperties.UserRolePolicy findUserRolePolicy(String role) {
        if (authProperties == null || role == null || role.isBlank()
                || authProperties.getRolePolicies().isEmpty()) {
            return null;
        }
        ServerAuthProperties.UserRolePolicy direct = authProperties.getRolePolicies().get(role);
        if (direct != null) {
            return direct.isEnabled() ? direct : null;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return authProperties.getRolePolicies().entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .filter(ServerAuthProperties.UserRolePolicy::isEnabled)
                .findFirst()
                .orElse(null);
    }

    private Optional<PolicyLayer> collectApiTokenLayer(Map<String, String> metadata) {
        String tokenId = firstNonBlank(metadata.get("apiTokenId"), metadata.get("apiToken.id"));
        String mode = firstNonBlank(metadata.get("apiToken.permissionMode"), metadata.get("apiToken.toolPermissionMode"));
        Set<String> approved = parseToolIds(firstNonBlank(metadata.get("apiToken.approvedToolIds"), metadata.get("apiToken.toolIds")));
        if (tokenId.isBlank() || (mode.isBlank() && approved.isEmpty())) {
            return Optional.empty();
        }
        // Token 是程序访问凭证，它只能收紧任务权限，不能绕过用户或设备策略。
        return Optional.of(new PolicyLayer(normalizeMode(mode), approved,
                "api-token:" + tokenId, "api-token", "API Token 权限范围"));
    }

    private String lookupBoundUserId(Map<String, String> metadata) {
        String explicit = firstNonBlank(metadata.get("device.boundUserId"), metadata.get("boundUserId"));
        if (!explicit.isBlank()) {
            return explicit;
        }
        String deviceId = firstNonBlank(metadata.get("deviceId"),
                firstNonBlank(metadata.get("device.id"), metadata.get("client.deviceId")));
        return deviceRegistryService.findActive(deviceId)
                .map(DeviceView::boundUserId)
                .orElse("");
    }

    private Optional<PolicyLayer> collectDeviceLayer(Map<String, String> metadata) {
        String deviceId = firstNonBlank(metadata.get("deviceId"),
                firstNonBlank(metadata.get("device.id"), metadata.get("client.deviceId")));
        Optional<DeviceView> device = deviceRegistryService.findActive(deviceId);
        if (device.isEmpty()) {
            return Optional.empty();
        }
        Set<String> approved = new LinkedHashSet<>(device.get().approvedToolIds() == null
                ? List.of()
                : device.get().approvedToolIds());
        return Optional.of(new PolicyLayer(normalizeMode(device.get().permissionMode()), normalizeToolIds(approved),
                "device:" + device.get().id(), "device", "设备权限绑定"));
    }

    private Optional<PolicyLayer> collectAgentLayer(Map<String, String> metadata) {
        if ("read-only".equalsIgnoreCase(firstNonBlank(metadata.get("agent.isolation.effective"),
                metadata.get("agent.isolation")))) {
            return Optional.of(new PolicyLayer("read-only", Set.of(),
                    "agent-isolation:read-only", "agent", "子 Agent 只读隔离"));
        }
        String mode = firstNonBlank(metadata.get("agent.permissionMode"), metadata.get("agent.approvalMode"));
        Set<String> approved = parseToolIds(firstNonBlank(metadata.get("agent.approvedToolIds"),
                metadata.get("agent.allowedToolIds")));
        if (mode.isBlank() && approved.isEmpty()) {
            return Optional.empty();
        }
        String agentId = firstNonBlank(metadata.get("agent.id"),
                firstNonBlank(metadata.get("agentId"), metadata.get("subAgent.id")));
        // Agent 级策略来自调度层 metadata，用于限制特定子 Agent 的工具能力，不依赖用户或设备身份。
        return Optional.of(new PolicyLayer(normalizeMode(mode), normalizeToolIds(approved),
                agentId.isBlank() ? "agent:metadata" : "agent:" + agentId,
                "agent", "Agent 级权限策略"));
    }

    private Optional<PolicyLayer> collectAgentRoleLayer(Map<String, String> metadata) {
        String role = firstNonBlank(metadata.get("agent.role"), metadata.get("agent.type"));
        if (role.isBlank() || properties == null || properties.getAgents().getPolicies().isEmpty()) {
            return Optional.empty();
        }
        ClawAgentProperties.AgentPolicy policy = findAgentPolicy(role);
        if (policy == null || !policy.isEnabled()) {
            return Optional.empty();
        }
        String mode = firstNonBlank(policy.getPermissionMode(), policy.getApprovalMode());
        Set<String> approved = normalizeToolIds(new LinkedHashSet<>(policy.getApprovedToolIds() == null
                ? List.of()
                : policy.getApprovedToolIds()));
        if (mode.isBlank() && approved.isEmpty()) {
            return Optional.empty();
        }
        // Agent 角色策略是配置模板；具体任务仍可用 agent.permissionMode 再收紧，但不能放宽其它维度。
        return Optional.of(new PolicyLayer(normalizeMode(mode), approved,
                "agent-role:" + role.trim(), "agent", "Agent 角色策略：" + role.trim()));
    }

    private ClawAgentProperties.AgentPolicy findAgentPolicy(String role) {
        Map<String, ClawAgentProperties.AgentPolicy> policies = properties.getAgents().getPolicies();
        ClawAgentProperties.AgentPolicy direct = policies.get(role);
        if (direct != null) {
            return direct;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return policies.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private PolicyLayer merge(List<PolicyLayer> layers) {
        PolicyLayer strictest = layers.get(0);
        for (PolicyLayer layer : layers) {
            if (modeWeight(layer.mode()) > modeWeight(strictest.mode())) {
                strictest = layer;
            }
        }
        Set<String> approved = mergeApprovedToolIds(layers);
        if ("read-only".equals(strictest.mode())) {
            // read-only 是最终强约束，解释视图也不应继续展示其它层交集出来的高危白名单。
            approved = Set.of();
        }
        String reason = "按 " + RESOLUTION_ORDER + " 合并 " + layers.size() + " 层策略，采用最严格模式 "
                + strictest.mode();
        return new PolicyLayer(strictest.mode(), approved, strictest.source(), strictest.scope(), reason);
    }

    private Set<String> mergeApprovedToolIds(List<PolicyLayer> layers) {
        List<Set<String>> nonEmpty = layers.stream()
                .map(PolicyLayer::approvedToolIds)
                .filter(ids -> !ids.isEmpty())
                .toList();
        if (nonEmpty.isEmpty()) {
            return Set.of();
        }
        Set<String> merged = new LinkedHashSet<>(nonEmpty.get(0));
        for (int i = 1; i < nonEmpty.size(); i++) {
            // 多维度同时给出白名单时取交集，避免用户或设备策略意外扩大工具权限。
            merged.retainAll(nonEmpty.get(i));
        }
        return Set.copyOf(merged);
    }

    private int modeWeight(String mode) {
        String normalized = normalizeMode(mode);
        int index = MODE_ORDER.indexOf(normalized);
        return index < 0 ? MODE_ORDER.indexOf("ask") : index;
    }

    private String inferExistingSource(Map<String, String> metadata) {
        String channelId = firstNonBlank(metadata.get("channel.id"), metadata.get("channelId"));
        if (!channelId.isBlank()) {
            return "channel:" + channelId;
        }
        return "task.metadata";
    }

    private String inferScope(String source) {
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("channel:")) {
            return "channel";
        }
        if (normalized.startsWith("user:")) {
            return "user";
        }
        if (normalized.startsWith("device:")) {
            return "device";
        }
        if (normalized.startsWith("api-token:")) {
            return "api-token";
        }
        if (normalized.startsWith("agent-isolation:")
                || normalized.startsWith("agent-role:")
                || normalized.startsWith("agent:")) {
            return "agent";
        }
        return "task";
    }

    private Set<String> parseToolIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(value.replace('[', ' ').replace(']', ' ').replace('"', ' ').replace('\'', ' ')
                        .split("[,;\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private Set<String> normalizeToolIds(Set<String> value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        value.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("full-access".equals(value)) {
            return value;
        }
        if ("full".equals(value) || "auto".equals(value) || "custom".equals(value)
                || "ask".equals(value) || "read-only".equals(value)) {
            return value;
        }
        return value.isBlank() ? "ask" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second.trim()) : first.trim();
    }

    private record PolicyLayer(
            String mode,
            Set<String> approvedToolIds,
            String source,
            String scope,
            String reason
    ) {
    }
}
