package com.github.clawagent.server.controller;

import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelAdapterReloadResult;
import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelAdapterDescriptor;
import com.github.clawagent.channel.ChannelInboundPayloadAdapter;
import com.github.clawagent.channel.ChannelInboundPayloadResult;
import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelOutboundClient;
import com.github.clawagent.channel.ChannelSendResult;
import com.github.clawagent.channel.ChannelStreamClientManager;
import com.github.clawagent.channel.ChannelStreamReloadResult;
import com.github.clawagent.channel.ChannelStreamStatus;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.server.dto.ChannelUserBindingRequest;
import com.github.clawagent.server.dto.ChannelUserBindingView;
import com.github.clawagent.server.dto.ChannelOutboundTestRequest;
import com.github.clawagent.server.dto.ChannelOutboundTestResponse;
import com.github.clawagent.server.service.ChannelUserBindingService;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ChannelController 是外部 IM/HTTP 入口的统一适配层。
 * 飞书、钉钉等平台后续只负责验签和事件解包，最终都应落到这里的统一入站模型。
 */
@RestController
@RequestMapping("/api/v1")
public class ChannelController {
    private static final Logger log = LoggerFactory.getLogger(ChannelController.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelAdapterRegistry channelAdapterRegistry;
    private final ChannelRouter channelRouter;
    private final ChannelOutboundClient channelOutboundClient;
    private final ChannelStreamClientManager channelStreamClientManager;
    private final AgentEventStore eventStore;
    private final ClawAgentProperties properties;
    private final ChannelUserBindingService channelUserBindingService;

    public ChannelController(ChannelRegistry channelRegistry, ChannelAdapterRegistry channelAdapterRegistry, ChannelRouter channelRouter,
                             ChannelOutboundClient channelOutboundClient,
                             ChannelStreamClientManager channelStreamClientManager,
                             @Qualifier("agentEventStore") AgentEventStore eventStore,
                             ClawAgentProperties properties,
                             ChannelUserBindingService channelUserBindingService) {
        this.channelRegistry = channelRegistry;
        this.channelAdapterRegistry = channelAdapterRegistry;
        this.channelRouter = channelRouter;
        this.channelOutboundClient = channelOutboundClient;
        this.channelStreamClientManager = channelStreamClientManager;
        this.eventStore = eventStore;
        this.properties = properties;
        this.channelUserBindingService = channelUserBindingService;
    }

    /**
     * 列出所有 Channel 配置，包含内置模板和用户保存的覆盖配置。
     */
    @GetMapping("/channels")
    public List<ChannelDefinition> channels() {
        return channelRegistry.list();
    }

    /**
     * 查看当前进程加载的 Channel adapter。
     */
    @GetMapping("/channels/adapters")
    public List<ChannelAdapterDescriptor> channelAdapters() {
        return channelAdapterRegistry.adapters();
    }

    /**
     * 重新扫描外部 Channel adapter jar。
     * 普通入站、出站和连通性检查会立即使用新注册表；已启动且支持 stop 的 Stream 会自动重启到新 adapter。
     */
    @PostMapping("/channels/adapters/reload")
    public ChannelAdapterReloadResult reloadChannelAdapters() {
        ChannelAdapterReloadResult result = channelAdapterRegistry.reloadExternalAdapters();
        ChannelStreamReloadResult streamReload = restartRunningStreamsAfterAdapterChange();
        recordChannelAudit("channel.adapters_reloaded", "Channel adapter 已重新扫描", null, null, Map.of(
                "candidateCount", String.valueOf(result.candidateCount()),
                "activeCount", String.valueOf(result.activeCount()),
                "streamRunningCount", String.valueOf(streamReload.runningCount()),
                "streamRestartedCount", String.valueOf(streamReload.restartedCount()),
                "streamFailedCount", String.valueOf(streamReload.failedCount())));
        return result;
    }

    /**
     * 导入外部 Channel adapter jar。
     * 上传只负责放入 adapter 目录并重新扫描，adapter 实现仍必须通过 ServiceLoader 暴露 ChannelRuntimeAdapter。
     */
    @PostMapping(value = "/channels/adapters/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChannelAdapterReloadResult uploadChannelAdapter(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 Channel adapter jar。");
        }
        String originalFilename = Optional.ofNullable(file.getOriginalFilename()).orElse("channel-adapter.jar");
        String safeFilename = Path.of(originalFilename).getFileName().toString();
        if (!safeFilename.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("Channel adapter 只能导入 .jar 文件。");
        }
        Path adapterDir = adapterUploadDirectory();
        Files.createDirectories(adapterDir);
        Path target = adapterDir.resolve(safeFilename).normalize();
        if (!target.startsWith(adapterDir)) {
            throw new IllegalArgumentException("非法的 adapter 文件名：" + originalFilename);
        }
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        ChannelAdapterReloadResult result = channelAdapterRegistry.reloadExternalAdapters();
        ChannelStreamReloadResult streamReload = restartRunningStreamsAfterAdapterChange();
        recordChannelAudit("channel.adapter_uploaded", "Channel adapter jar 已导入", null, null, Map.of(
                "file", safeFilename,
                "candidateCount", String.valueOf(result.candidateCount()),
                "activeCount", String.valueOf(result.activeCount()),
                "streamRunningCount", String.valueOf(streamReload.runningCount()),
                "streamRestartedCount", String.valueOf(streamReload.restartedCount()),
                "streamFailedCount", String.valueOf(streamReload.failedCount())));
        return result;
    }

    /**
     * 删除外部 Channel adapter jar。
     * 删除后立即重新扫描 adapter；已启动且支持 stop 的 Stream 会自动重启到当前生效 adapter。
     */
    @DeleteMapping("/channels/adapters/{filename}")
    public Map<String, Object> deleteChannelAdapter(@PathVariable("filename") String filename) throws IOException {
        String safeFilename = Path.of(Optional.ofNullable(filename).orElse("")).getFileName().toString();
        if (safeFilename.isBlank() || !safeFilename.equals(filename) || !safeFilename.toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("只能删除 adapter 目录下的 .jar 文件。");
        }
        Path adapterDir = adapterUploadDirectory();
        Path target = adapterDir.resolve(safeFilename).normalize();
        if (!target.startsWith(adapterDir)) {
            throw new IllegalArgumentException("非法的 adapter 文件名：" + filename);
        }
        boolean deleted = Files.deleteIfExists(target);
        ChannelAdapterReloadResult result = channelAdapterRegistry.reloadExternalAdapters();
        ChannelStreamReloadResult streamReload = restartRunningStreamsAfterAdapterChange();
        recordChannelAudit("channel.adapter_deleted", "Channel adapter jar 已删除", null, null, Map.of(
                "file", safeFilename,
                "deleted", String.valueOf(deleted),
                "candidateCount", String.valueOf(result.candidateCount()),
                "activeCount", String.valueOf(result.activeCount()),
                "streamRunningCount", String.valueOf(streamReload.runningCount()),
                "streamRestartedCount", String.valueOf(streamReload.restartedCount()),
                "streamFailedCount", String.valueOf(streamReload.failedCount())));
        return Map.of(
                "file", safeFilename,
                "deleted", deleted,
                "reload", result,
                "streamReload", streamReload);
    }

    /**
     * 查看指定 Channel 的完整配置。
     */
    @GetMapping("/channels/{channelId}")
    public ChannelDefinition channel(@PathVariable("channelId") String channelId) {
        return channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
    }

    /**
     * 创建或保存一个新的 Channel 配置。
     */
    @PostMapping("/channels")
    public ChannelDefinition createChannel(@RequestBody ChannelDefinition request) {
        log.info("channel create requested id={} type={}", request == null ? "" : request.id(), request == null ? "" : request.type());
        ChannelDefinition channel = channelRegistry.save(request);
        recordChannelAudit("channel.created", "Channel 配置已创建", channel, Map.of("action", "create"));
        return channel;
    }

    /**
     * 更新指定 Channel 配置，路径中的 channelId 始终作为最终主键。
     */
    @PutMapping("/channels/{channelId}")
    public ChannelDefinition updateChannel(
            @PathVariable("channelId") String channelId,
            @RequestBody ChannelDefinition request) {
        ChannelDefinition safeRequest = request == null
                ? new ChannelDefinition(channelId, channelId, channelId, false, "ask", List.of(), "", Map.of(), null, null)
                : request;
        log.info("channel update requested id={} type={}", channelId, safeRequest.type());
        // URL 里的 channelId 是配置主键，避免前端 body 里的 id 和路径不一致造成重复配置。
        ChannelDefinition channel = channelRegistry.save(new ChannelDefinition(
                channelId,
                safeRequest.name(),
                safeRequest.type(),
                safeRequest.enabled(),
                safeRequest.approvalMode(),
                safeRequest.approvedToolIds(),
                safeRequest.inboundPath(),
                safeRequest.metadata(),
                safeRequest.createdAt(),
                safeRequest.updatedAt()));
        recordChannelAudit("channel.updated", "Channel 配置已更新", channel, Map.of("action", "update"));
        return channel;
    }

    /**
     * 删除用户保存的 Channel 覆盖配置；内置模板会在下次列表查询时恢复默认值。
     */
    @DeleteMapping("/channels/{channelId}")
    public Map<String, Object> deleteChannel(@PathVariable("channelId") String channelId) {
        boolean deleted = channelRegistry.delete(channelId);
        recordChannelAudit("channel.deleted", deleted ? "Channel 配置已删除" : "Channel 删除未命中", channelId, null,
                Map.of("deleted", String.valueOf(deleted)));
        return Map.of("deleted", deleted, "channelId", channelId);
    }

    /**
     * 列出当前 Channel 的外部用户绑定。
     * 绑定只保存外部用户与本地用户的映射，不保存飞书、钉钉或 DDIO 凭证。
     */
    @GetMapping("/channels/{channelId}/users")
    public List<ChannelUserBindingView> channelUserBindings(@PathVariable("channelId") String channelId) {
        return requireChannelUserBindingService().list(channelId);
    }

    /**
     * 绑定外部 IM 用户到本地用户，让通道入站任务可以复用本地用户权限策略。
     */
    @PostMapping("/channels/{channelId}/users")
    public ChannelUserBindingView bindChannelUser(
            @PathVariable("channelId") String channelId,
            @RequestBody ChannelUserBindingRequest request) {
        ChannelUserBindingView binding = requireChannelUserBindingService().bind(channelId, request);
        recordChannelAudit("channel.user_bound", "Channel 外部用户已绑定本地用户", channelId, null, Map.of(
                "externalUserId", binding.externalUserId(),
                "localUserId", binding.localUserId()));
        return binding;
    }

    /**
     * 解绑外部 IM 用户。这里使用 query 参数承载 externalUserId，避免平台用户 ID 中的特殊字符被路径拆分。
     */
    @DeleteMapping("/channels/{channelId}/users")
    public Map<String, Object> unbindChannelUser(
            @PathVariable("channelId") String channelId,
            @RequestParam("externalUserId") String externalUserId) {
        boolean unbound = requireChannelUserBindingService().unbind(channelId, externalUserId);
        recordChannelAudit("channel.user_unbound", unbound ? "Channel 外部用户已解绑" : "Channel 外部用户解绑未命中",
                channelId, null, Map.of(
                        "externalUserId", stringValue(externalUserId),
                        "unbound", String.valueOf(unbound)));
        return Map.of("channelId", channelId, "externalUserId", stringValue(externalUserId), "unbound", unbound);
    }

    /**
     * 检查 Channel 本地配置和可安全探测的平台凭证。
     */
    @PostMapping("/channels/{channelId}/health")
    public ChannelConnectivityStatus checkChannelHealth(@PathVariable("channelId") String channelId) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        ChannelConnectivityStatus status = channelOutboundClient.checkConnectivity(channel);
        recordChannelAudit("channel.health_checked", "Channel 连通性检查完成", channel, Map.of(
                "status", status.status(),
                "ready", String.valueOf(status.ready()),
                "probedRemote", String.valueOf(status.probedRemote())));
        return status;
    }

    /**
     * 从管理台主动发送一条测试消息，用于验证飞书/钉钉出站配置。
     */
    @PostMapping("/channels/{channelId}/outbound/test")
    public ChannelOutboundTestResponse testChannelOutbound(
            @PathVariable("channelId") String channelId,
            @RequestBody ChannelOutboundTestRequest request) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        String text = firstNonBlank(request == null ? "" : request.text(), "ClawAgent Channel 出站测试").orElse("ClawAgent Channel 出站测试");
        Map<String, String> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("source", "admin-channel-outbound-test");
        if (request != null && request.metadata() != null) {
            sourceMetadata.putAll(request.metadata());
        }
        ChannelInboundMessage source = new ChannelInboundMessage(
                channel.id(),
                request == null ? "" : stringValue(request.externalConversationId()),
                firstNonBlank(request == null ? "" : request.externalUserId(), "console").orElse("console"),
                "text",
                text,
                sourceMetadata,
                Map.of());
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "externalConversationId", source.externalConversationId());
        putIfPresent(details, "externalUserId", source.externalUserId());
        details.put("textLength", String.valueOf(text.length()));
        if (!channel.enabled()) {
            recordChannelAudit("channel.outbound_tested", "Channel 出站测试被配置拦截", channel,
                    Map.of("status", "blocked", "reason", "disabled"));
            return new ChannelOutboundTestResponse(channel.id(), channel.type(), false, "blocked",
                    "当前 Channel 未启用。", details);
        }
        ChannelSendResult sendResult;
        try {
            sendResult = channelOutboundClient.sendTextDetailed(channel, source, text);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            putIfPresent(details, "error", error);
            sendResult = ChannelSendResult.failed(error, details);
        }
        details.putAll(sendResult.details());
        recordChannelAudit("channel.outbound_tested", sendResult.sent() ? "Channel 出站测试已发送" : "Channel 出站测试失败", channel,
                Map.of("status", sendResult.status(), "textLength", String.valueOf(text.length())));
        return new ChannelOutboundTestResponse(
                channel.id(),
                channel.type(),
                sendResult.sent(),
                sendResult.status(),
                firstNonBlank(sendResult.message(), "出站测试未发送，请检查 Channel 是否启用、平台凭证和飞书会话 ID。")
                        .orElse("出站测试未发送，请检查 Channel 是否启用、平台凭证和飞书会话 ID。"),
                details);
    }

    /**
     * 启动需要常驻连接的 Channel SDK 客户端，例如飞书长连接或钉钉 Stream。
     */
    @PostMapping("/channels/{channelId}/stream/start")
    public ChannelStreamStatus startChannelStream(@PathVariable("channelId") String channelId) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        ChannelStreamStatus status = channelStreamClientManager.start(channel);
        recordChannelAudit("channel.stream_started", "Channel Stream 启动请求完成", channel, streamAudit(status));
        return status;
    }

    /**
     * 停止当前进程内的 Channel SDK 客户端。
     */
    @PostMapping("/channels/{channelId}/stream/stop")
    public ChannelStreamStatus stopChannelStream(@PathVariable("channelId") String channelId) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        ChannelStreamStatus status = channelStreamClientManager.stop(channel);
        recordChannelAudit("channel.stream_stopped", "Channel Stream 停止请求完成", channel, streamAudit(status));
        return status;
    }

    /**
     * 查看当前进程内的 Channel SDK 客户端运行状态。
     */
    @GetMapping("/channels/{channelId}/stream/status")
    public ChannelStreamStatus channelStreamStatus(@PathVariable("channelId") String channelId) {
        ChannelDefinition channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        return channelStreamClientManager.status(channel);
    }

    /**
     * 接收通用入站消息，消息体中的 channelId 决定具体 Channel，缺省时使用 api。
     */
    @PostMapping("/channels/inbound")
    public Object receiveChannelMessage(
            @RequestBody Map<String, Object> payload,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> query) {
        Map<String, Object> enriched = enrichTransport(payload, headers, query);
        // 通用入口优先用 payload 里的 channelId/channel 反查配置，飞书加密回调才能拿到 Encrypt Key。
        ChannelDefinition channel = resolvePayloadChannel(enriched).flatMap(this::resolveInboundChannel).orElse(null);
        ChannelInboundPayloadResult adapted = ChannelInboundPayloadAdapter.adaptWithResponse(channelAdapterRegistry, channel,
                channel == null ? null : channel.id(), enriched);
        if (adapted.hasImmediateResponse()) {
            return adapted.responseBody();
        }
        if (channel != null) {
            return channelRouter.receive(channel.id(), adapted.message());
        }
        return channelRouter.receive(adapted.message());
    }

    /**
     * 接收指定 Channel 的入站消息，用于飞书、钉钉或外部 IM 网关按固定路径转发。
     */
    @PostMapping("/channels/{channelId}/inbound")
    public Object receiveChannelMessage(
            @PathVariable("channelId") String channelId,
            @RequestBody Map<String, Object> payload,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> query) {
        ChannelDefinition channel = resolveInboundChannel(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel 不存在：" + channelId));
        ChannelInboundPayloadResult adapted = ChannelInboundPayloadAdapter.adaptWithResponse(channelAdapterRegistry, channel, channel.id(),
                enrichTransport(payload, headers, query));
        if (adapted.hasImmediateResponse()) {
            return adapted.responseBody();
        }
        return channelRouter.receive(channel.id(), adapted.message());
    }

    Optional<ChannelDefinition> resolveInboundChannel(String channelIdOrType) {
        String candidate = stringValue(channelIdOrType);
        if (candidate.isBlank()) {
            return Optional.empty();
        }
        Optional<ChannelDefinition> exact = channelRegistry.find(candidate);
        if (exact.filter(ChannelDefinition::enabled).isPresent()) {
            return exact;
        }
        // 平台兼容路径可能传 feishu/dingtalk 这类 type，而不是 YML 生成的 feishu-main。
        Optional<ChannelDefinition> enabledByType = findEnabledDefaultByType(candidate)
                .or(() -> findFirstEnabledByType(candidate));
        return enabledByType.or(() -> exact);
    }

    private Optional<ChannelDefinition> findEnabledDefaultByType(String channelType) {
        return channelRegistry.list().stream()
                .filter(channel -> channelType.equalsIgnoreCase(channel.type()))
                .filter(ChannelDefinition::enabled)
                .filter(channel -> "true".equalsIgnoreCase(metadata(channel, "channel.isDefaultAccount")))
                .findFirst();
    }

    private Optional<ChannelDefinition> findFirstEnabledByType(String channelType) {
        return channelRegistry.list().stream()
                .filter(channel -> channelType.equalsIgnoreCase(channel.type()))
                .filter(ChannelDefinition::enabled)
                .findFirst();
    }

    private Path adapterUploadDirectory() {
        List<String> adapterPaths = properties == null ? List.of() : properties.getChannels().getAdapterPath();
        String configuredPath = adapterPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .findFirst()
                .orElse(".clawagent/channels/adapters");
        Path resolved = resolveRuntimePath(configuredPath);
        if (resolved.toString().toLowerCase().endsWith(".jar")) {
            return Optional.ofNullable(resolved.getParent()).orElse(Path.of(".")).toAbsolutePath().normalize();
        }
        return resolved.toAbsolutePath().normalize();
    }

    private ChannelStreamReloadResult restartRunningStreamsAfterAdapterChange() {
        if (channelStreamClientManager == null || channelRegistry == null) {
            return ChannelStreamReloadResult.empty();
        }
        // 外部 adapter 变更后，长连接必须重新绑定到当前生效 adapter；不支持 stop 的 SDK 会保留诊断状态。
        return channelStreamClientManager.restartRunningStreams(channelRegistry.list());
    }

    private ChannelUserBindingService requireChannelUserBindingService() {
        if (channelUserBindingService == null) {
            throw new IllegalStateException("Channel 用户绑定服务未启用");
        }
        return channelUserBindingService;
    }

    private Path resolveRuntimePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path directPath = userDir.resolve(path).normalize();
        if (Files.exists(directPath)) {
            return directPath;
        }
        // 和 starter 的运行时目录解析保持一致：从 server 子模块启动时也优先复用仓库级 .clawagent。
        if (path.getNameCount() > 0 && ".clawagent".equals(path.getName(0).toString())) {
            Path cursor = userDir;
            while (cursor != null) {
                Path base = cursor.resolve(".clawagent").normalize();
                if (Files.exists(base)) {
                    return cursor.resolve(path).normalize();
                }
                cursor = cursor.getParent();
            }
        }
        return directPath;
    }

    private Map<String, Object> enrichTransport(Map<String, Object> payload, Map<String, String> headers, Map<String, String> query) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (payload != null) {
            enriched.putAll(payload);
        }
        // headers/query 参与平台验签和事件审计，但不改变平台原始 payload 字段。
        enriched.put("_headers", headers == null ? Map.of() : new LinkedHashMap<>(headers));
        enriched.put("_query", query == null ? Map.of() : new LinkedHashMap<>(query));
        return enriched;
    }

    private Optional<String> resolvePayloadChannel(Map<String, Object> payload) {
        return firstNonBlank(
                stringValue(payload.get("channelId")),
                stringValue(payload.get("channel")),
                stringValue(payload.get("type")))
                .filter(value -> !"url_verification".equals(value));
    }

    private Optional<String> firstNonBlank(String... values) {
        if (values == null) {
            return Optional.empty();
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String metadata(ChannelDefinition channel, String key) {
        if (channel.metadata() == null) {
            return "";
        }
        return channel.metadata().getOrDefault(key, "");
    }

    private void recordChannelAudit(String type, String message, ChannelDefinition channel, Map<String, String> extra) {
        if (eventStore == null) {
            return;
        }
        if (channel == null) {
            recordChannelAudit(type, message, null, null, extra);
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "channelId", channel.id());
        putIfPresent(details, "name", channel.name());
        putIfPresent(details, "channelType", channel.type());
        putIfPresent(details, "approvalMode", channel.approvalMode());
        details.put("enabled", String.valueOf(channel.enabled()));

        details.put("approvedToolCount", String.valueOf(channel.approvedToolIds() == null ? 0 : channel.approvedToolIds().size()));
        if (extra != null) {
            details.putAll(extra);
        }
        // Channel metadata 可能包含平台回调配置，审计只记录配置摘要。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private Map<String, String> streamAudit(ChannelStreamStatus status) {
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "streamMode", status.mode());
        putIfPresent(details, "streamStatus", status.status());
        return details;
    }

    private void recordChannelAudit(String type, String message, String channelId, String channelType,
                                    Map<String, String> extra) {
        if (eventStore == null) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "channelId", channelId);
        putIfPresent(details, "channelType", channelType);
        if (extra != null) {
            details.putAll(extra);
        }
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
