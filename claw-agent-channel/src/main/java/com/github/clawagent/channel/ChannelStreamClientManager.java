package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel Stream/长连接生命周期门面。
 * 平台 SDK 细节由 ChannelRuntimeAdapter 提供，这里只维护运行表和统一状态。
 */
public class ChannelStreamClientManager {
    private final ChannelAdapterRegistry adapterRegistry;
    private final Map<String, ChannelStreamHandle> runningClients = new ConcurrentHashMap<>();

    public ChannelStreamClientManager(ChannelRouter channelRouter) {
        this(ChannelAdapterRegistry.builtin(channelRouter), true);
    }

    public ChannelStreamClientManager(ChannelAdapterRegistry adapterRegistry, boolean ignored) {
        this.adapterRegistry = adapterRegistry;
    }

    public ChannelStreamStatus start(ChannelDefinition channel) {
        if (channel == null) {
            return ChannelStreamStatus.failed("", "", "", "Channel 不存在");
        }
        if (runningClients.containsKey(channel.id())) {
            return status(channel);
        }
        return adapterRegistry.find(safeType(channel))
                .map(adapter -> startWithAdapter(adapter, channel))
                .orElseGet(() -> ChannelStreamStatus.unsupported(channel.id(), safeType(channel), streamMode(channel),
                        "当前 Channel 类型不需要 SDK 长连接。"));
    }

    public ChannelStreamStatus stop(ChannelDefinition channel) {
        if (channel == null) {
            return ChannelStreamStatus.failed("", "", "", "Channel 不存在");
        }
        ChannelStreamHandle handle = runningClients.remove(channel.id());
        if (handle == null) {
            return ChannelStreamStatus.stopped(channel.id(), safeType(channel), streamMode(channel), "Channel Stream 未运行。");
        }
        if (handle.stopper() == null) {
            return ChannelStreamStatus.unsupported(channel.id(), safeType(channel), handle.mode(),
                    "当前 SDK Client 没有公开 stop 方法，已从本地运行表移除；需要重启进程才能彻底断开旧连接。");
        }
        try {
            handle.stopper().stop();
            return ChannelStreamStatus.stopped(channel.id(), safeType(channel), handle.mode(), "Channel Stream 已停止。");
        } catch (Exception e) {
            return ChannelStreamStatus.failed(channel.id(), safeType(channel), handle.mode(), safeError("停止 Channel Stream 失败", e));
        }
    }

    public ChannelStreamStatus status(ChannelDefinition channel) {
        if (channel == null) {
            return ChannelStreamStatus.failed("", "", "", "Channel 不存在");
        }
        ChannelStreamHandle handle = runningClients.get(channel.id());
        if (handle == null) {
            return ChannelStreamStatus.stopped(channel.id(), safeType(channel), streamMode(channel), "Channel Stream 未运行。");
        }
        return ChannelStreamStatus.running(channel.id(), safeType(channel), handle.mode(), "Channel Stream 正在运行。");
    }

    private ChannelStreamStatus startWithAdapter(ChannelRuntimeAdapter adapter, ChannelDefinition channel) {
        try {
            ChannelStreamHandle handle = adapter.startStream(channel);
            runningClients.put(channel.id(), handle);
            return ChannelStreamStatus.running(channel.id(), safeType(channel), handle.mode(), "Channel Stream 已启动。");
        } catch (UnsupportedOperationException e) {
            return ChannelStreamStatus.unsupported(channel.id(), safeType(channel), adapter.streamMode(channel),
                    "当前 Channel 类型不需要 SDK 长连接。");
        } catch (Exception e) {
            return ChannelStreamStatus.failed(channel.id(), safeType(channel), adapter.streamMode(channel), safeError("启动 Channel Stream 失败", e));
        }
    }

    private String streamMode(ChannelDefinition channel) {
        return adapterRegistry.find(safeType(channel))
                .map(adapter -> adapter.streamMode(channel))
                .orElse(metadataValue(channel, "connectionMode", "connectionModeEnv", "http"));
    }

    private String metadataValue(ChannelDefinition channel, String directKey, String envKey, String fallback) {
        if (channel == null || channel.metadata() == null) {
            return fallback;
        }
        String direct = stringValue(channel.metadata().get(directKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = stringValue(channel.metadata().get(envKey));
        String envValue = envName.isBlank() ? "" : stringValue(System.getenv(envName));
        return envValue.isBlank() ? fallback : envValue;
    }

    private String safeType(ChannelDefinition channel) {
        return channel.type() == null ? "" : channel.type().trim().toLowerCase();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeError(String prefix, Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = e.getClass().getSimpleName();
        }
        return prefix + "：" + detail;
    }
}
