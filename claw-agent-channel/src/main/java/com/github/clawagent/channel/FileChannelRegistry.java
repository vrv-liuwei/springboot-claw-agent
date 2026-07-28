package com.github.clawagent.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.spi.ChannelRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 基于本地 JSON 文件的 Channel 注册表。
 * 这是个人本地部署默认实现，企业版后续可以替换为数据库或配置中心实现。
 */
public class FileChannelRegistry implements ChannelRegistry {
    private static final TypeReference<List<ChannelDefinition>> CHANNEL_LIST_TYPE = new TypeReference<>() {};
    private static final List<String> ACCOUNT_STYLE_INTERNAL_METADATA = List.of(
            "channel.configStyle",
            "channel.accountId",
            "channel.defaultAccount",
            "channel.isDefaultAccount",
            "channel.source",
            "channel.readOnly",
            "name",
            "enabled",
            "approvalMode",
            "approvedToolIds",
            "inboundPath",
            "accountId",
            "id",
            "createdAt",
            "updatedAt");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path storePath;
    private final ChannelAdapterRegistry adapterRegistry;
    private final List<ChannelDefinition> configuredChannels;

    public FileChannelRegistry(Path storePath) {
        this(storePath, ChannelAdapterRegistry.builtin(null));
    }

    public FileChannelRegistry(Path storePath, ChannelAdapterRegistry adapterRegistry) {
        this(storePath, adapterRegistry, List.of());
    }

    public FileChannelRegistry(Path storePath, ChannelAdapterRegistry adapterRegistry, List<ChannelDefinition> configuredChannels) {
        this.storePath = storePath;
        this.adapterRegistry = adapterRegistry;
        this.configuredChannels = configuredChannels == null ? List.of() : configuredChannels.stream()
                .filter(channel -> channel != null)
                .map(this::markConfigured)
                .map(this::normalize)
                .toList();
    }

    @Override
    public synchronized List<ChannelDefinition> list() {
        Map<String, ChannelDefinition> channels = new LinkedHashMap<>();
        for (ChannelDefinition builtin : builtinChannels()) {
            channels.put(builtin.id(), builtin);
        }
        for (ChannelDefinition saved : readSaved()) {
            channels.put(saved.id(), saved);
        }
        // application.yml / 本地覆盖 YAML 是部署侧显式配置，优先级高于管理台落盘的 channels.json。
        for (ChannelDefinition configured : configuredChannels) {
            channels.put(configured.id(), configured);
        }
        return new ArrayList<>(channels.values());
    }

    @Override
    public synchronized Optional<ChannelDefinition> find(String channelId) {
        String id = normalizeId(channelId);
        return list().stream().filter(channel -> channel.id().equals(id)).findFirst();
    }

    @Override
    public synchronized ChannelDefinition save(ChannelDefinition request) {
        ChannelDefinition normalized = normalize(request);
        if (isConfiguredChannel(normalized.id())) {
            throw new IllegalStateException("Channel 由 YAML 配置管理，不能保存到 channels.json 覆盖：" + normalized.id());
        }
        SavedChannelFile savedFile = readSavedFile();
        Map<String, ChannelDefinition> channels = new LinkedHashMap<>();
        for (ChannelDefinition saved : savedFile.channels()) {
            channels.put(saved.id(), saved);
        }
        ChannelDefinition existing = channels.get(normalized.id());
        Instant createdAt = existing == null ? Instant.now() : existing.createdAt();
        channels.put(normalized.id(), normalized.withTimestamps(createdAt, Instant.now()));
        writeSaved(new ArrayList<>(channels.values()), savedFile.accountStyle(), savedFile.channelsWrapper());
        return channels.get(normalized.id());
    }

    @Override
    public synchronized boolean delete(String channelId) {
        String id = normalizeId(channelId);
        SavedChannelFile savedFile = readSavedFile();
        List<ChannelDefinition> saved = new ArrayList<>(savedFile.channels());
        boolean removed = saved.removeIf(channel -> channel.id().equals(id));
        if (removed) {
            writeSaved(saved, savedFile.accountStyle(), savedFile.channelsWrapper());
        }
        return removed;
    }

    private List<ChannelDefinition> builtinChannels() {
        return adapterRegistry.builtinChannels();
    }

    private ChannelDefinition markConfigured(ChannelDefinition source) {
        Map<String, String> metadata = source.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.metadata());
        metadata.putIfAbsent("channel.source", "yaml");
        metadata.putIfAbsent("channel.readOnly", "true");
        return new ChannelDefinition(
                source.id(),
                source.name(),
                source.type(),
                source.enabled(),
                source.approvalMode(),
                source.approvedToolIds(),
                source.inboundPath(),
                metadata,
                source.createdAt(),
                source.updatedAt());
    }

    private boolean isConfiguredChannel(String channelId) {
        return configuredChannels.stream().anyMatch(channel -> channel.id().equals(channelId));
    }

    private ChannelDefinition normalize(ChannelDefinition source) {
        if (source == null) {
            throw new IllegalArgumentException("Channel 配置不能为空");
        }
        String id = normalizeId(source.id());
        if (id.isBlank()) {
            throw new IllegalArgumentException("Channel ID 不能为空");
        }
        String type = normalizeId(firstNonBlank(source.type(), id));
        String name = firstNonBlank(source.name(), id);
        String approvalMode = normalizeApprovalMode(source.approvalMode());
        Map<String, String> metadata = source.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.metadata());
        return new ChannelDefinition(
                id,
                name,
                type,
                source.enabled(),
                approvalMode,
                source.approvedToolIds() == null ? List.of() : normalizeList(source.approvedToolIds()),
                firstNonBlank(source.inboundPath(), "/api/v1/channels/" + id + "/inbound"),
                metadata,
                source.createdAt(),
                source.updatedAt());
    }

    private List<ChannelDefinition> readSaved() {
        return readSavedFile().channels();
    }

    private SavedChannelFile readSavedFile() {
        if (!Files.exists(storePath)) {
            return SavedChannelFile.flat(List.of());
        }
        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            if (root == null || root.isNull() || root.isMissingNode()) {
                return SavedChannelFile.flat(List.of());
            }
            if (root.isArray()) {
                List<ChannelDefinition> channels = objectMapper.readValue(json, CHANNEL_LIST_TYPE).stream()
                        .map(this::normalize)
                        .toList();
                return SavedChannelFile.flat(channels);
            }
            if (root.isObject()) {
                boolean channelsWrapper = root.has("channels");
                JsonNode channelsNode = channelsWrapper ? root.path("channels") : root;
                return new SavedChannelFile(readAccountStyleChannels(channelsNode), true, channelsWrapper);
            }
            return SavedChannelFile.flat(List.of());
        } catch (IOException e) {
            throw new IllegalStateException("读取 Channel 配置失败：" + storePath, e);
        }
    }

    public static List<ChannelDefinition> fromAccountStyleConfig(Map<String, Object> channelsConfig) {
        if (channelsConfig == null || channelsConfig.isEmpty()) {
            return List.of();
        }
        FileChannelRegistry parser = new FileChannelRegistry(Path.of("channels.json"), new ChannelAdapterRegistry(List.of()));
        JsonNode channelsNode = parser.objectMapper.valueToTree(channelsConfig);
        return parser.readAccountStyleChannels(channelsNode);
    }

    private void writeSaved(List<ChannelDefinition> channels) {
        writeSaved(channels, false, true);
    }

    private void writeSaved(List<ChannelDefinition> channels, boolean accountStyle, boolean channelsWrapper) {
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            JsonNode payload = accountStyle ? accountStylePayload(channels, channelsWrapper) : objectMapper.valueToTree(channels);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), payload);
        } catch (IOException e) {
            throw new IllegalStateException("保存 Channel 配置失败：" + storePath, e);
        }
    }

    private JsonNode accountStylePayload(List<ChannelDefinition> channels, boolean channelsWrapper) {
        ObjectNode channelsNode = objectMapper.createObjectNode();
        Map<String, List<ChannelDefinition>> byType = new LinkedHashMap<>();
        for (ChannelDefinition channel : channels) {
            ChannelDefinition normalized = normalize(channel);
            byType.computeIfAbsent(normalized.type(), key -> new ArrayList<>()).add(normalized);
        }
        byType.forEach((type, typedChannels) -> channelsNode.set(type, accountStyleChannelNode(type, typedChannels)));
        if (!channelsWrapper) {
            return channelsNode;
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("channels", channelsNode);
        return root;
    }

    private ObjectNode accountStyleChannelNode(String type, List<ChannelDefinition> typedChannels) {
        ObjectNode channelNode = objectMapper.createObjectNode();
        String defaultAccount = resolveDefaultAccount(type, typedChannels);
        if (!defaultAccount.isBlank()) {
            channelNode.put("defaultAccount", defaultAccount);
        }
        ObjectNode accountsNode = objectMapper.createObjectNode();
        for (ChannelDefinition channel : typedChannels) {
            accountsNode.set(resolveAccountId(type, defaultAccount, channel), accountStyleAccountNode(channel));
        }
        channelNode.set("accounts", accountsNode);
        return channelNode;
    }

    private ObjectNode accountStyleAccountNode(ChannelDefinition channel) {
        ObjectNode accountNode = objectMapper.createObjectNode();
        accountNode.put("name", channel.name());
        accountNode.put("enabled", channel.enabled());
        accountNode.put("approvalMode", channel.approvalMode());
        if (channel.approvedToolIds() != null && !channel.approvedToolIds().isEmpty()) {
            ArrayNode approvedToolIds = accountNode.putArray("approvedToolIds");
            channel.approvedToolIds().forEach(approvedToolIds::add);
        }
        accountNode.put("inboundPath", channel.inboundPath());
        // metadata 里保留平台 adapter 需要的真实配置，channel.* 这类展开时产生的内部标记不写回。
        Map<String, String> metadata = channel.metadata() == null ? Map.of() : channel.metadata();
        metadata.forEach((key, value) -> {
            if (ACCOUNT_STYLE_INTERNAL_METADATA.contains(key) || value == null) {
                return;
            }
            putAccountStyleMetadata(accountNode, key, value);
        });
        return accountNode;
    }

    private void putAccountStyleMetadata(ObjectNode accountNode, String key, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (key == null || key.isBlank() || trimmed.isBlank()) {
            return;
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                accountNode.set(key, objectMapper.readTree(trimmed));
                return;
            } catch (IOException ignored) {
                // 元数据可能只是普通字符串，JSON 解析失败时按原文本写回。
            }
        }
        accountNode.put(key, trimmed);
    }

    private String resolveDefaultAccount(String type, List<ChannelDefinition> channels) {
        for (ChannelDefinition channel : channels) {
            String configuredDefault = channel.metadata() == null ? "" : firstNonBlank(channel.metadata().get("channel.defaultAccount"), "");
            if (!configuredDefault.isBlank()) {
                return normalizeId(configuredDefault);
            }
        }
        for (ChannelDefinition channel : channels) {
            String accountId = channel.metadata() == null ? "" : firstNonBlank(channel.metadata().get("channel.accountId"), "");
            if (channel.id().equals(type) && !accountId.isBlank() && !"default".equals(accountId)) {
                return normalizeId(accountId);
            }
        }
        return "";
    }

    private String resolveAccountId(String type, String defaultAccount, ChannelDefinition channel) {
        String accountId = channel.metadata() == null ? "" : firstNonBlank(channel.metadata().get("channel.accountId"), "");
        if (!accountId.isBlank()) {
            return normalizeId(accountId);
        }
        if (channel.id().equals(type)) {
            return firstNonBlank(defaultAccount, "default");
        }
        String prefix = type + "-";
        return channel.id().startsWith(prefix) ? normalizeId(channel.id().substring(prefix.length())) : channel.id();
    }

    private List<ChannelDefinition> readAccountStyleChannels(JsonNode channelsNode) {
        if (channelsNode == null || !channelsNode.isObject()) {
            return List.of();
        }
        List<ChannelDefinition> channels = new ArrayList<>();
        channelsNode.fields().forEachRemaining(entry -> {
            String channelType = normalizeId(entry.getKey());
            JsonNode channelConfig = entry.getValue();
            JsonNode accountsNode = channelConfig == null ? null : channelConfig.path("accounts");
            if (accountsNode != null && accountsNode.isArray()) {
                accountsNode.forEach(account -> channels.add(toAccountChannel(channelType, channelConfig, accountId(account, ""), account)));
            } else if (accountsNode != null && accountsNode.isObject()) {
                accountsNode.fields().forEachRemaining(accountEntry ->
                        channels.add(toAccountChannel(channelType, channelConfig, normalizeId(accountEntry.getKey()), accountEntry.getValue())));
            } else if (channelConfig != null && channelConfig.isObject() && !channelConfig.isEmpty()) {
                channels.add(toAccountChannel(channelType, channelConfig, "", channelConfig));
            }
        });
        return channels.stream().map(this::normalize).toList();
    }

    private ChannelDefinition toAccountChannel(String channelType, JsonNode channelConfig, String accountId, JsonNode account) {
        String normalizedType = normalizeId(channelType);
        String normalizedAccountId = normalizeId(firstNonBlank(accountId(account, accountId), "default"));
        String defaultAccount = stringValue(channelConfig.path("defaultAccount"));
        String normalizedDefaultAccount = normalizeId(defaultAccount);
        boolean isDefaultAccount = !normalizedDefaultAccount.isBlank() && normalizedDefaultAccount.equals(normalizedAccountId);
        String id = normalizedAccountId.equals("default") || isDefaultAccount ? normalizedType : normalizedType + "-" + normalizedAccountId;
        Map<String, String> metadata = new LinkedHashMap<>();
        // 平台级配置作为账号默认值，账号级配置可以覆盖同名 key。
        flattenMetadata(metadata, channelConfig, "accounts", "inboundEnabled", "outboundEnabled");
        flattenMetadata(metadata, account, "inboundEnabled", "outboundEnabled");
        metadata.put("adapter", firstNonBlank(metadata.get("adapter"), "builtin"));
        metadata.put("channel.configStyle", "accounts");
        metadata.put("channel.accountId", normalizedAccountId);
        if (!defaultAccount.isBlank()) {
            metadata.put("channel.defaultAccount", defaultAccount);
            metadata.put("channel.isDefaultAccount", String.valueOf(isDefaultAccount));
        }
        return new ChannelDefinition(
                id,
                firstNonBlank(stringValue(account.path("name")), stringValue(channelConfig.path("name")), id),
                normalizedType,
                booleanValue(account.path("enabled"), booleanValue(channelConfig.path("enabled"), false)),
                firstNonBlank(stringValue(account.path("approvalMode")), stringValue(channelConfig.path("approvalMode")), "ask"),
                stringList(account.path("approvedToolIds"), stringList(channelConfig.path("approvedToolIds"), List.of())),
                firstNonBlank(stringValue(account.path("inboundPath")), stringValue(channelConfig.path("inboundPath")), "/api/v1/channels/" + id + "/inbound"),
                metadata,
                null,
                null);
    }

    private String accountId(JsonNode account, String fallback) {
        return firstNonBlank(
                stringValue(account == null ? null : account.path("accountId")),
                stringValue(account == null ? null : account.path("id")),
                fallback);
    }

    private void flattenMetadata(Map<String, String> metadata, JsonNode node, String... ignoredKeys) {
        if (node == null || !node.isObject()) {
            return;
        }
        List<String> ignored = ignoredKeys == null ? List.of() : List.of(ignoredKeys);
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (ignored.contains(key) || value == null || value.isNull() || value.isMissingNode()) {
                return;
            }
            if (value.isObject()) {
                value.fields().forEachRemaining(child -> putMetadata(metadata, key + "." + child.getKey(), child.getValue()));
            } else {
                putMetadata(metadata, key, value);
            }
        });
    }

    private void putMetadata(Map<String, String> metadata, String key, JsonNode value) {
        if (key == null || key.isBlank() || value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        if (value.isContainerNode()) {
            metadata.put(key, value.toString());
            return;
        }
        metadata.put(key, value.asText());
    }

    private boolean booleanValue(JsonNode value, boolean fallback) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : Boolean.parseBoolean(text);
    }

    private List<String> stringList(JsonNode value, List<String> fallback) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return fallback;
        }
        if (!value.isArray()) {
            String text = value.asText("");
            return text.isBlank() ? fallback : List.of(text);
        }
        List<String> values = new ArrayList<>();
        value.forEach(item -> {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values.isEmpty() ? fallback : values;
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeApprovalMode(String value) {
        String mode = normalizeId(value);
        return switch (mode) {
            case "auto", "full", "custom" -> mode;
            default -> "ask";
        };
    }

    private List<String> normalizeList(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String firstNonBlank(String first, String second, String fallback) {
        return firstNonBlank(firstNonBlank(first, second), fallback);
    }

    private String stringValue(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? "" : value.asText("").trim();
    }

    private record SavedChannelFile(List<ChannelDefinition> channels, boolean accountStyle, boolean channelsWrapper) {
        private static SavedChannelFile flat(List<ChannelDefinition> channels) {
            return new SavedChannelFile(channels, false, true);
        }
    }
}
