package com.github.clawagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.clawagent.spi.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于文件的 MCP 注册表。
 * MCP Server 配置以标准 mcp.json 为准；连接进程、客户端对象和工具映射属于运行态，只在当前进程内保存。
 */
public class FileMcpRegistry implements McpRegistry {
    private static final Logger log = LoggerFactory.getLogger(FileMcpRegistry.class);

    /** 当前 JVM 内已连接的 MCP Client，key 为 serverId；重启后需要重新连接。 */
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    /** 已连接 MCP Server 暴露出的工具缓存，只有 connect/refresh-tools 后才有值。 */
    private final Map<String, List<McpToolDescriptor>> tools = new LinkedHashMap<>();
    /** 已连接 MCP Server 暴露出的资源缓存，只有 connect/refresh-resources 后才有值。 */
    private final Map<String, List<McpResourceDescriptor>> resources = new LinkedHashMap<>();
    /** 已连接 MCP Server 暴露出的提示词缓存，只有 connect/refresh-prompts 后才有值。 */
    private final Map<String, List<McpPromptDescriptor>> prompts = new LinkedHashMap<>();
    /** 当前 JVM 内的 MCP Server 状态缓存，配置本身仍以文件为准。 */
    private final Map<String, McpServerStatus> runtimeStatuses = new LinkedHashMap<>();
    /** 当前 JVM 内的 MCP Server 状态说明，例如失败原因。 */
    private final Map<String, String> runtimeMessages = new LinkedHashMap<>();
    /** 统一工具注册表，MCP tools 会适配成 AgentTool 注册进去。 */
    private final AgentToolRegistry toolRegistry;
    /** mcp.json 解析器，兼容标准 mcpServers 和历史数组格式。 */
    private final McpConfigImporter importer = new McpConfigImporter();
    /** JSON 序列化器，负责把 MCP 配置写回标准 mcpServers 格式。 */
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** MCP 配置文件列表；读取时全量合并，写入时优先写已有文件或第一个文件。 */
    private final List<Path> storePaths;

    public FileMcpRegistry(AgentToolRegistry toolRegistry) {
        this(toolRegistry, List.of());
    }

    public FileMcpRegistry(AgentToolRegistry toolRegistry, Path storePath) {
        this(toolRegistry, storePath == null ? List.of() : List.of(storePath));
    }

    public FileMcpRegistry(AgentToolRegistry toolRegistry, List<Path> storePaths) {
        this.toolRegistry = toolRegistry;
        this.storePaths = storePaths == null ? List.of() : List.copyOf(storePaths);
        log.info("mcp file registry initialized paths={}", this.storePaths);
        logConfiguredServers();
    }

    @Override
    public synchronized McpServerRegistration test(McpServerConfig config) {
        McpServerConfig normalized = normalize(config, config.enabled());
        McpServerRegistration registration = new McpServerRegistration(normalized);
        if (!normalized.enabled()) {
            registration.markStatus(McpServerStatus.DISABLED, "server disabled");
            return registration;
        }
        // 测试连接只启动临时 client，不保存配置，也不注册 MCP tools。
        try (McpClient client = createClient(normalized)) {
            client.initialize();
            List<McpToolDescriptor> descriptors = client.listTools();
            registration.markStatus(McpServerStatus.CONNECTED, "test connected, tools=" + descriptors.size());
            log.info("mcp server test succeeded id={} tools={}", normalized.id(), descriptors.size());
            return registration;
        } catch (RuntimeException e) {
            registration.markStatus(McpServerStatus.FAILED, e.getMessage());
            log.warn("mcp server test failed id={} message={}", normalized.id(), e.getMessage());
            return registration;
        }
    }

    @Override
    public synchronized McpServerRegistration register(McpServerConfig config) {
        McpServerConfig normalized = normalize(config, config.enabled());
        saveServer(normalized);
        log.info("mcp server saved id={} name={} paths={}", normalized.id(), normalized.name(), storePaths);
        return registrationWithRuntimeStatus(normalized);
    }

    @Override
    public synchronized McpServerRegistration update(String serverId, McpServerConfig config) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("MCP Server id 不能为空");
        }
        McpServerConfig update = withServerId(serverId, config);
        McpServerConfig normalized = normalize(update, update.enabled());
        saveServer(normalized);
        if (!normalized.enabled()) {
            clearRuntimeState(serverId, true);
            markStatus(serverId, McpServerStatus.DISABLED, "updated and disabled");
        } else if (clients.containsKey(serverId)) {
            // 配置变更后旧 client 已不可信，等待用户或下一次调用重新连接。
            clearRuntimeState(serverId, true);
            markStatus(serverId, McpServerStatus.REGISTERED, "updated, reconnect required");
        }
        log.info("mcp server updated id={} name={} paths={}", normalized.id(), normalized.name(), storePaths);
        return registrationWithRuntimeStatus(normalized);
    }

    @Override
    public synchronized List<McpServerRegistration> importServers(String json) {
        List<McpServerRegistration> registrations = new ArrayList<>();
        for (McpServerConfig config : importer.parse(json)) {
            registrations.add(register(config));
        }
        return registrations;
    }

    @Override
    public synchronized McpServerRegistration connect(String serverId) {
        McpServerRegistration registration = require(serverId);
        if (!registration.config().enabled()) {
            markStatus(serverId, McpServerStatus.DISABLED, "server disabled");
            registration.markStatus(McpServerStatus.DISABLED, "server disabled");
            return registration;
        }
        try {
            markStatus(serverId, McpServerStatus.CONNECTING, "connecting");
            McpClient oldClient = clients.remove(serverId);
            if (oldClient != null) {
                oldClient.close();
            }

            // 真正连接时才创建长期运行的 MCP client，并在刷新工具后挂到 AgentToolRegistry。
            McpClient client = createClient(registration.config());
            client.initialize();
            clients.put(serverId, client);
            refreshTools(serverId);
            safeRefreshResources(serverId);
            safeRefreshPrompts(serverId);
            markStatus(serverId, McpServerStatus.CONNECTED, "connected");
            return registrationWithRuntimeStatus(registration.config());
        } catch (RuntimeException e) {
            clearRuntimeState(serverId, true);
            markStatus(serverId, McpServerStatus.FAILED, e.getMessage());
            throw e;
        }
    }

    @Override
    public synchronized List<McpServerRegistration> connectAll() {
        List<McpServerRegistration> registrations = new ArrayList<>();
        for (McpServerConfig config : loadAllConfigs().values()) {
            if (!config.enabled()) {
                // disabled 配置只记录状态，不主动启动本地进程。
                markStatus(config.id(), McpServerStatus.DISABLED, "server disabled");
                registrations.add(registrationWithRuntimeStatus(config));
                continue;
            }
            try {
                // 启动自动连接时逐个 server 隔离处理，避免一个 MCP 失败拖垮整个应用。
                registrations.add(connect(config.id()));
                log.info("mcp server auto connected id={} tools={}", config.id(), tools.getOrDefault(config.id(), List.of()).size());
            } catch (RuntimeException e) {
                markStatus(config.id(), McpServerStatus.FAILED, e.getMessage());
                registrations.add(registrationWithRuntimeStatus(config));
                log.warn("mcp server auto connect failed id={} message={}", config.id(), e.getMessage());
            }
        }
        log.info("mcp auto connect finished count={} connected={} failed={}",
                registrations.size(),
                registrations.stream().filter(item -> item.status() == McpServerStatus.CONNECTED).count(),
                registrations.stream().filter(item -> item.status() == McpServerStatus.FAILED).count());
        return registrations;
    }

    @Override
    public synchronized McpServerRegistration disconnect(String serverId) {
        McpServerRegistration registration = require(serverId);
        clearRuntimeState(serverId, true);
        markStatus(serverId, McpServerStatus.REGISTERED, "disconnected");
        return registrationWithRuntimeStatus(registration.config());
    }

    @Override
    public synchronized List<McpToolDescriptor> refreshTools(String serverId) {
        McpClient client = connectedClient(serverId);
        try {
            List<McpToolDescriptor> descriptors = client.listTools();
            tools.put(serverId, descriptors);
            toolRegistry.unregisterByPrefix("mcp." + serverId + ".");
            McpServerConfig config = loadAllConfigs().get(serverId);
            List<String> autoApprove = config == null ? List.of() : config.autoApprove();

            // MCP tool 使用 mcp.<serverId>.<toolName> 前缀注册，避免和本地工具、Skill 工具冲突。
            for (McpToolDescriptor descriptor : descriptors) {
                // MCP autoApprove 正式接入 ToolExecutionGuard：未命中白名单的 MCP tool 默认是 high risk。
                toolRegistry.registerOrReplace(new McpAgentTool(descriptor, client, isAutoApproved(descriptor, autoApprove)));
            }
            markStatus(serverId, McpServerStatus.CONNECTED, "tools refreshed count=" + descriptors.size());
            return descriptors;
        } catch (RuntimeException e) {
            // tools/list 失败通常说明连接已不可用；清理旧工具，防止模型继续调用已断开的 MCP tool。
            clearRuntimeState(serverId, true);
            markStatus(serverId, McpServerStatus.FAILED, e.getMessage());
            throw e;
        }
    }

    static boolean isAutoApproved(McpToolDescriptor descriptor, List<String> autoApprove) {
        if (autoApprove == null || autoApprove.isEmpty()) {
            return false;
        }
        String toolName = descriptor.name();
        String agentToolId = descriptor.agentToolId();
        for (String rule : autoApprove) {
            String normalized = rule == null ? "" : rule.trim();
            if (normalized.isBlank()) {
                continue;
            }
            // 支持标准 MCP toolName、ClawAgent toolId，以及 * / 前缀* 这类常见通配规则。
            if ("*".equals(normalized)
                    || normalized.equals(toolName)
                    || normalized.equals(agentToolId)
                    || wildcardMatches(normalized, toolName)
                    || wildcardMatches(normalized, agentToolId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcardMatches(String rule, String value) {
        if (rule == null || value == null || !rule.endsWith("*")) {
            return false;
        }
        String prefix = rule.substring(0, rule.length() - 1);
        return !prefix.isBlank() && value.startsWith(prefix);
    }

    private void safeRefreshResources(String serverId) {
        try {
            refreshResources(serverId);
        } catch (RuntimeException e) {
            // resources 是 MCP 可选能力；失败不能影响 tools 的注册和调用。
            resources.put(serverId, List.of());
            log.debug("mcp resources refresh skipped serverId={} message={}", serverId, e.getMessage());
        }
    }

    private void safeRefreshPrompts(String serverId) {
        try {
            refreshPrompts(serverId);
        } catch (RuntimeException e) {
            // prompts 是 MCP 可选能力；失败不能影响 tools 的注册和调用。
            prompts.put(serverId, List.of());
            log.debug("mcp prompts refresh skipped serverId={} message={}", serverId, e.getMessage());
        }
    }

    @Override
    public synchronized List<McpToolDescriptor> listTools(String serverId) {
        return new ArrayList<>(tools.getOrDefault(serverId, List.of()));
    }

    @Override
    public synchronized List<McpResourceDescriptor> refreshResources(String serverId) {
        McpClient client = connectedClient(serverId);
        List<McpResourceDescriptor> descriptors = client.listResources();
        resources.put(serverId, descriptors);
        log.info("mcp resources refreshed serverId={} count={}", serverId, descriptors.size());
        return descriptors;
    }

    @Override
    public synchronized List<McpResourceDescriptor> listResources(String serverId) {
        return new ArrayList<>(resources.getOrDefault(serverId, List.of()));
    }

    @Override
    public synchronized List<McpPromptDescriptor> refreshPrompts(String serverId) {
        McpClient client = connectedClient(serverId);
        List<McpPromptDescriptor> descriptors = client.listPrompts();
        prompts.put(serverId, descriptors);
        log.info("mcp prompts refreshed serverId={} count={}", serverId, descriptors.size());
        return descriptors;
    }

    @Override
    public synchronized List<McpPromptDescriptor> listPrompts(String serverId) {
        return new ArrayList<>(prompts.getOrDefault(serverId, List.of()));
    }

    @Override
    public synchronized McpResourceContent readResource(String serverId, String uri) {
        McpClient client = connectedClient(serverId);
        McpResourceContent content = client.readResource(uri);
        log.info("mcp resource read serverId={} uri={}", serverId, uri);
        return content;
    }

    @Override
    public synchronized McpPromptContent getPrompt(String serverId, String name, Map<String, Object> arguments) {
        McpClient client = connectedClient(serverId);
        McpPromptContent content = client.getPrompt(name, arguments);
        log.info("mcp prompt get serverId={} name={}", serverId, name);
        return content;
    }

    @Override
    public synchronized Optional<McpServerRegistration> find(String serverId) {
        return Optional.ofNullable(loadAllConfigs().get(serverId)).map(this::registrationWithRuntimeStatus);
    }

    @Override
    public synchronized List<McpServerRegistration> list() {
        return loadAllConfigs().values().stream()
                .map(this::registrationWithRuntimeStatus)
                .toList();
    }

    @Override
    public synchronized void disable(String serverId) {
        Optional<McpServerRegistration> registration = find(serverId);
        if (registration.isPresent()) {
            disconnect(serverId);
            McpServerConfig disabled = normalize(registration.get().config(), false);
            saveServer(disabled);
            markStatus(serverId, McpServerStatus.DISABLED, "disabled by user");
        }
    }

    @Override
    public synchronized boolean delete(String serverId) {
        boolean removed = false;
        clearRuntimeState(serverId, true);
        for (Path storePath : storePaths) {
            if (storePath == null || !Files.exists(storePath)) {
                continue;
            }
            Map<String, McpServerConfig> configs = readConfigs(storePath);
            if (configs.remove(serverId) != null) {
                writeConfigs(storePath, configs);
                removed = true;
            }
        }
        runtimeStatuses.remove(serverId);
        runtimeMessages.remove(serverId);
        log.warn("mcp server deleted id={} removed={}", serverId, removed);
        return removed;
    }

    private McpServerRegistration require(String serverId) {
        return find(serverId).orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在：" + serverId));
    }

    private void clearRuntimeState(String serverId, boolean closeClient) {
        McpClient client = clients.remove(serverId);
        if (closeClient && client != null) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.debug("mcp client close ignored serverId={} message={}", serverId, e.getMessage());
            }
        }
        // MCP 运行态由 client、缓存和 AgentToolRegistry 三部分组成，清理时必须保持一致。
        tools.remove(serverId);
        resources.remove(serverId);
        prompts.remove(serverId);
        toolRegistry.unregisterByPrefix("mcp." + serverId + ".");
    }

    private McpClient connectedClient(String serverId) {
        McpClient client = clients.get(serverId);
        if (client != null) {
            return client;
        }
        Optional<McpServerRegistration> registration = find(serverId);
        if (registration.isPresent() && registration.get().config().enabled()) {
            // MCP client 是运行态对象，应用重启或子进程退出后可能丢失；这里按需重连恢复。
            connect(serverId);
            client = clients.get(serverId);
        }
        if (client == null) {
            throw new IllegalStateException("MCP 服务未连接：" + serverId);
        }
        return client;
    }

    private McpClient createClient(McpServerConfig config) {
        // 不同传输只在 client 层分叉，registry 仍复用同一套保存、状态和工具注册逻辑。
        return switch (config.transport()) {
            case STDIO -> new StdioMcpClient(config);
            case HTTP, STREAMABLE_HTTP -> new HttpMcpClient(config);
            case SSE -> new SseMcpClient(config);
        };
    }

    private McpServerConfig normalize(McpServerConfig config, boolean enabled) {
        String id = config.id() == null || config.id().isBlank() ? slug(config.name()) : config.id().trim();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("MCP Server id/name 不能为空");
        }
        String name = config.name() == null || config.name().isBlank() ? id : config.name().trim();
        McpTransport transport = config.transport() == null ? McpTransport.STDIO : config.transport();
        return new McpServerConfig(
                id,
                name,
                transport,
                config.endpoint(),
                config.command(),
                config.args(),
                config.env(),
                config.headers(),
                config.cwd(),
                config.timeoutSeconds(),
                config.autoApprove(),
                enabled);
    }

    private Map<String, McpServerConfig> loadAllConfigs() {
        Map<String, McpServerConfig> configsById = new LinkedHashMap<>();
        for (Path storePath : storePaths) {
            migrateLegacyServersJson(storePath);
            if (storePath == null || !Files.exists(storePath)) {
                continue;
            }
            for (McpServerConfig config : readConfigs(storePath).values()) {
                // 多文件配置按 path 顺序合并；后面的文件同名配置会覆盖前面的配置。
                configsById.put(config.id(), config);
            }
        }
        return configsById;
    }

    private void migrateLegacyServersJson(Path targetPath) {
        if (targetPath == null || Files.exists(targetPath)) {
            return;
        }
        Path parent = targetPath.getParent();
        if (parent == null) {
            return;
        }
        Path legacyPath = parent.resolve("servers.json");
        if (!Files.exists(legacyPath)) {
            return;
        }
        try {
            Map<String, McpServerConfig> configs = readConfigs(legacyPath);
            if (configs.isEmpty()) {
                log.warn("legacy mcp servers.json found but empty path={}", legacyPath);
                return;
            }
            Files.createDirectories(parent);
            // 兼容历史版本：旧 servers.json 是数组格式，新文件统一写成标准 mcpServers 对象。
            objectMapper.writeValue(targetPath.toFile(), toStandardMcpConfig(configs));
            log.info("legacy mcp servers migrated from={} to={} count={}", legacyPath, targetPath, configs.size());
        } catch (IOException e) {
            throw new IllegalStateException("历史 MCP Server 配置迁移失败：" + e.getMessage(), e);
        }
    }

    private void logConfiguredServers() {
        Map<String, McpServerConfig> configs = loadAllConfigs();
        if (configs.isEmpty()) {
            // 启动时明确提示没有读到 MCP 配置，避免用户误以为 MCP 已自动加载。
            log.warn("mcp registry loaded no servers paths={}", storePaths);
            return;
        }
        // 启动日志打印 server id 列表，方便确认 mcp.json 是否按预期被读取。
        log.info("mcp registry loaded count={} serverIds={} paths={}", configs.size(), configs.keySet(), storePaths);
    }

    private void saveServer(McpServerConfig config) {
        Path storePath = storePathFor(config.id());
        if (storePath == null) {
            throw new IllegalStateException("未配置 clawagent.mcp.path，无法保存 MCP Server");
        }
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, McpServerConfig> configs = readConfigs(storePath);
            // name/id 已存在时覆盖；不存在时追加到当前 mcp.json。
            configs.put(config.id(), config);
            objectMapper.writeValue(storePath.toFile(), toStandardMcpConfig(configs));
            log.debug("mcp server file saved id={} count={} path={}", config.id(), configs.size(), storePath);
        } catch (IOException e) {
            throw new IllegalStateException("MCP Server 配置保存失败：" + e.getMessage(), e);
        }
    }

    private void writeConfigs(Path storePath, Map<String, McpServerConfig> configs) {
        try {
            Path parent = storePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(storePath.toFile(), toStandardMcpConfig(configs));
            log.debug("mcp server file rewritten count={} path={}", configs.size(), storePath);
        } catch (IOException e) {
            throw new IllegalStateException("MCP Server 配置保存失败：" + e.getMessage(), e);
        }
    }

    private Map<String, McpServerConfig> readConfigs(Path storePath) {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        if (storePath == null || !Files.exists(storePath)) {
            log.debug("mcp config file not found path={}", storePath);
            return configs;
        }
        try {
            for (McpServerConfig config : importer.parse(Files.readString(storePath))) {
                McpServerConfig normalized = normalize(config, config.enabled());
                configs.put(normalized.id(), normalized);
            }
            return configs;
        } catch (IOException e) {
            throw new IllegalStateException("MCP Server 配置读取失败：" + e.getMessage(), e);
        }
    }

    private Path storePathFor(String serverId) {
        for (Path storePath : storePaths) {
            if (readConfigs(storePath).containsKey(serverId)) {
                return storePath;
            }
        }
        return primaryStorePath();
    }

    private Map<String, Object> toStandardMcpConfig(Map<String, McpServerConfig> configs) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        for (McpServerConfig config : configs.values()) {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("type", toStandardType(config.transport()));
            if (config.command() != null && !config.command().isBlank()) {
                server.put("command", config.command());
            }
            if (!config.args().isEmpty()) {
                server.put("args", config.args());
            }
            if (config.endpoint() != null && !config.endpoint().isBlank()) {
                server.put("url", config.endpoint());
            }
            if (!config.cwd().isBlank()) {
                server.put("cwd", config.cwd());
            }
            if (!config.env().isEmpty()) {
                server.put("env", config.env());
            }
            if (!config.headers().isEmpty()) {
                server.put("headers", config.headers());
            }
            if (config.timeoutSeconds() != 30) {
                server.put("timeout", config.timeoutSeconds());
            }
            if (!config.autoApprove().isEmpty()) {
                server.put("autoApprove", config.autoApprove());
            }
            if (!config.enabled()) {
                server.put("disabled", true);
            }
            mcpServers.put(config.id(), server);
        }
        root.put("mcpServers", mcpServers);
        return root;
    }

    private Path primaryStorePath() {
        return storePaths.isEmpty() ? null : storePaths.get(0);
    }

    private String toStandardType(McpTransport transport) {
        if (transport == null) {
            return "stdio";
        }
        return switch (transport) {
            case STDIO -> "stdio";
            case SSE -> "sse";
            case STREAMABLE_HTTP -> "streamableHttp";
            case HTTP -> "streamableHttp";
        };
    }

    private McpServerRegistration registrationWithRuntimeStatus(McpServerConfig config) {
        McpServerRegistration registration = new McpServerRegistration(config);
        if (!config.enabled()) {
            registration.markStatus(McpServerStatus.DISABLED, runtimeMessages.getOrDefault(config.id(), "server disabled"));
            return registration;
        }
        if (clients.containsKey(config.id())) {
            registration.markStatus(McpServerStatus.CONNECTED, runtimeMessages.getOrDefault(config.id(), "connected"));
            return registration;
        }
        McpServerStatus status = runtimeStatuses.get(config.id());
        if (status != null) {
            registration.markStatus(status, runtimeMessages.get(config.id()));
        }
        return registration;
    }

    private void markStatus(String serverId, McpServerStatus status, String message) {
        runtimeStatuses.put(serverId, status);
        runtimeMessages.put(serverId, message);
    }

    private String slug(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
    }

    private McpServerConfig withServerId(String serverId, McpServerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("MCP Server 配置不能为空");
        }
        return new McpServerConfig(
                serverId.trim(),
                config.name(),
                config.transport(),
                config.endpoint(),
                config.command(),
                config.args(),
                config.env(),
                config.headers(),
                config.cwd(),
                config.timeoutSeconds(),
                config.autoApprove(),
                config.enabled());
    }
}
