package com.github.clawagent.channel;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import com.github.clawagent.core.ChannelDefinition;

/**
 * Channel adapter 注册表。
 * 内置 adapter 和外部 jar adapter 都进入同一张表，业务门面只按 channel.type 查询能力。
 */
public class ChannelAdapterRegistry implements AutoCloseable {
    private final List<Path> adapterPaths;
    private final boolean builtinMode;
    private final List<AdapterCandidate> fixedCandidates;

    private volatile List<ChannelRuntimeAdapter> adapters = List.of();
    private volatile List<ChannelAdapterDescriptor> adapterDescriptors = List.of();
    private volatile List<URLClassLoader> externalClassLoaders = List.of();

    public ChannelAdapterRegistry(List<ChannelRuntimeAdapter> adapters) {
        this.adapterPaths = List.of();
        this.builtinMode = false;
        this.fixedCandidates = toCandidates(adapters, "spring-bean");
        installCandidates(this.fixedCandidates, List.of());
    }

    public ChannelAdapterRegistry(List<ChannelRuntimeAdapter> adapters, List<Path> adapterPaths) {
        this.adapterPaths = List.copyOf(adapterPaths == null ? List.of() : adapterPaths);
        this.builtinMode = true;
        this.fixedCandidates = toCandidates(adapters, "spring-bean");
        reloadExternalAdapters();
    }

    private void installCandidates(List<AdapterCandidate> candidates, List<URLClassLoader> newExternalClassLoaders) {
        Map<String, AdapterCandidate> deduplicated = new LinkedHashMap<>();
        for (AdapterCandidate candidate : candidates == null ? List.<AdapterCandidate>of() : candidates) {
            ChannelRuntimeAdapter adapter = candidate.adapter();
            if (adapter != null && adapter.type() != null && !adapter.type().isBlank()) {
                deduplicated.put(adapter.type().trim().toLowerCase(), candidate);
            }
        }
        this.adapters = deduplicated.values().stream().map(AdapterCandidate::adapter).toList();
        this.adapterDescriptors = descriptors(candidates, deduplicated);
        this.externalClassLoaders = List.copyOf(newExternalClassLoaders == null ? List.of() : newExternalClassLoaders);
    }

    public static ChannelAdapterRegistry builtin(ChannelRouter channelRouter) {
        return new ChannelAdapterRegistry(List.of(), List.of());
    }

    public static ChannelAdapterRegistry builtin(ChannelRouter channelRouter, List<Path> adapterPaths) {
        return new ChannelAdapterRegistry(List.of(), adapterPaths);
    }

    private List<AdapterCandidate> buildBuiltinCandidates() {
        List<AdapterCandidate> adapters = new ArrayList<>(fixedCandidates);
        loadServiceAdapters(adapters, Thread.currentThread().getContextClassLoader());
        return adapters;
    }

    private static void loadServiceAdapters(List<AdapterCandidate> adapters, ClassLoader classLoader) {
        loadServiceAdapters(adapters, classLoader, "service-loader");
    }

    private static void loadServiceAdapters(List<AdapterCandidate> adapters, ClassLoader classLoader, String source) {
        // 外部 adapter 通过 META-INF/services 暴露；后加载的同 type adapter 会覆盖内置实现。
        ServiceLoader<ChannelRuntimeAdapter> loader = ServiceLoader.load(ChannelRuntimeAdapter.class, classLoader);
        var iterator = loader.iterator();
        while (true) {
            boolean hasNext;
            try {
                hasNext = iterator.hasNext();
            } catch (ServiceConfigurationError | RuntimeException ignored) {
                return;
            }
            if (!hasNext) {
                return;
            }
            try {
                adapters.add(new AdapterCandidate(iterator.next(), source));
            } catch (ServiceConfigurationError | RuntimeException ignored) {
                // 单个外部 adapter 加载失败不应影响内置 Channel；后续管理页再展示 adapter 诊断信息。
            }
        }
    }

    private static List<URLClassLoader> loadExternalJarAdapters(List<AdapterCandidate> adapters, List<Path> adapterPaths) {
        List<URL> urls = discoverJarUrls(adapterPaths);
        if (urls.isEmpty()) {
            return List.of();
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URLClassLoader classLoader = new URLClassLoader(urls.toArray(URL[]::new), parent);
        loadServiceAdapters(adapters, classLoader, "external-jar");
        return List.of(classLoader);
    }

    private static List<URL> discoverJarUrls(List<Path> adapterPaths) {
        if (adapterPaths == null || adapterPaths.isEmpty()) {
            return List.of();
        }
        List<URL> urls = new ArrayList<>();
        for (Path path : adapterPaths) {
            if (path == null || !Files.exists(path)) {
                continue;
            }
            try {
                if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                    urls.add(path.toUri().toURL());
                } else if (Files.isDirectory(path)) {
                    try (var stream = Files.list(path)) {
                        stream.filter(candidate -> Files.isRegularFile(candidate)
                                        && candidate.toString().toLowerCase().endsWith(".jar"))
                                .sorted()
                                .map(ChannelAdapterRegistry::toUrl)
                                .forEach(urls::add);
                    }
                }
            } catch (Exception ignored) {
                // 单个 adapter 目录不可读不应影响内置 Channel 启动，管理页后续再展示诊断信息。
            }
        }
        return urls;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法加载 Channel adapter jar：" + path, e);
        }
    }

    public Optional<ChannelRuntimeAdapter> find(String channelType) {
        return adapters.stream().filter(adapter -> adapter.supports(channelType)).findFirst();
    }

    public Optional<ChannelRuntimeAdapter> detect(Map<String, Object> payload) {
        return adapters.stream().filter(adapter -> adapter.detectInbound(payload)).findFirst();
    }

    public List<ChannelAdapterDescriptor> adapters() {
        return adapterDescriptors;
    }

    public synchronized ChannelAdapterReloadResult reloadExternalAdapters() {
        if (!builtinMode) {
            installCandidates(fixedCandidates, List.of());
            return reloadResult();
        }
        List<AdapterCandidate> candidates = buildBuiltinCandidates();
        List<URLClassLoader> newExternalClassLoaders = loadExternalJarAdapters(candidates, adapterPaths);
        List<URLClassLoader> oldExternalClassLoaders = externalClassLoaders;
        installCandidates(candidates, newExternalClassLoaders);
        closeClassLoaders(oldExternalClassLoaders);
        return reloadResult();
    }

    public List<ChannelDefinition> builtinChannels() {
        Instant now = Instant.EPOCH;
        return List.of(
                new ChannelDefinition("webui", "Web 管理台", "webui", true, "ask",
                        List.of(), "/api/v1/tasks/stream", Map.of("builtin", "true"), now, now),
                new ChannelDefinition("api", "通用 API", "api", true, "ask",
                        List.of(), "/api/v1/channels/inbound", Map.of("builtin", "true"), now, now),
                new ChannelDefinition("feishu", "飞书", "feishu", false, "ask",
                        List.of(), "/api/v1/channels/feishu/inbound", Map.of(
                        "adapter", "builtin",
                        "adapterLevel", "official-http",
                        "connectionMode", "http",
                        "verificationTokenEnv", "FEISHU_VERIFICATION_TOKEN",
                        "encryptKeyEnv", "FEISHU_ENCRYPT_KEY",
                        "appIdEnv", "FEISHU_APP_ID",
                        "appSecretEnv", "FEISHU_APP_SECRET"), now, now),
                new ChannelDefinition("dingtalk", "钉钉", "dingtalk", false, "ask",
                        List.of(), "/api/v1/channels/dingtalk/inbound", Map.of(
                        "adapter", "builtin",
                        "adapterLevel", "official-http",
                        "connectionMode", "http",
                        "webhookUrlEnv", "DINGTALK_WEBHOOK_URL",
                        "secretEnv", "DINGTALK_SECRET",
                        "clientIdEnv", "DINGTALK_CLIENT_ID",
                        "clientSecretEnv", "DINGTALK_CLIENT_SECRET"), now, now),
                new ChannelDefinition("ddio", "DDIO", "ddio", false, "ask",
                        List.of(), "/ddio/message", Map.of(
                        "adapter", "builtin",
                        "adapterLevel", "official-http",
                        "connectionMode", "http",
                        "appIdEnv", "DDIO_APP_ID",
                        "appSecretEnv", "DDIO_APP_SECRET",
                        "baseUrlEnv", "DDIO_BASE_URL"), now, now)
        );
    }

    @Override
    public void close() {
        closeClassLoaders(externalClassLoaders);
    }

    private ChannelAdapterReloadResult reloadResult() {
        List<ChannelAdapterDescriptor> descriptors = adapters();
        return new ChannelAdapterReloadResult(
                descriptors.size(),
                (int) descriptors.stream().filter(ChannelAdapterDescriptor::active).count(),
                descriptors);
    }

    private static void closeClassLoaders(List<URLClassLoader> classLoaders) {
        for (URLClassLoader classLoader : classLoaders == null ? List.<URLClassLoader>of() : classLoaders) {
            try {
                classLoader.close();
            } catch (Exception ignored) {
                // 关闭失败只影响外部 jar 文件句柄释放，不影响进程退出。
            }
        }
    }

    private static List<AdapterCandidate> toCandidates(List<ChannelRuntimeAdapter> adapters, String source) {
        return (adapters == null ? List.<ChannelRuntimeAdapter>of() : adapters).stream()
                .map(adapter -> new AdapterCandidate(adapter, source))
                .toList();
    }

    private static List<ChannelAdapterDescriptor> descriptors(List<AdapterCandidate> candidates,
                                                              Map<String, AdapterCandidate> activeCandidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ChannelAdapterDescriptor> descriptors = new ArrayList<>();
        for (AdapterCandidate candidate : candidates) {
            ChannelRuntimeAdapter adapter = candidate.adapter();
            if (adapter == null || adapter.type() == null || adapter.type().isBlank()) {
                continue;
            }
            String type = adapter.type().trim().toLowerCase();
            AdapterCandidate active = activeCandidates.get(type);
            descriptors.add(new ChannelAdapterDescriptor(
                    type,
                    adapter.getClass().getName(),
                    candidate.source(),
                    codeLocation(adapter),
                    active != null && active.adapter() == adapter));
        }
        return List.copyOf(descriptors);
    }

    private static String codeLocation(ChannelRuntimeAdapter adapter) {
        try {
            var source = adapter.getClass().getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? "" : source.getLocation().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private record AdapterCandidate(ChannelRuntimeAdapter adapter, String source) {
    }
}
