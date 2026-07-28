package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    public ChannelStreamReloadResult restartRunningStreams(List<ChannelDefinition> channels) {
        List<String> runningIds = new ArrayList<>(runningClients.keySet());
        if (runningIds.isEmpty()) {
            return ChannelStreamReloadResult.empty();
        }
        Map<String, ChannelDefinition> latestChannels = (channels == null ? List.<ChannelDefinition>of() : channels).stream()
                .filter(channel -> channel != null)
                .collect(Collectors.toMap(ChannelDefinition::id, channel -> channel, (left, right) -> right));
        List<ChannelStreamStatus> statuses = new ArrayList<>();
        int restarted = 0;
        int stopped = 0;
        int unsupported = 0;
        int failed = 0;
        for (String channelId : runningIds) {
            ChannelDefinition channel = latestChannels.get(channelId);
            if (channel == null) {
                ChannelStreamStatus status = removeMissingChannelStream(channelId);
                statuses.add(status);
                failed++;
                continue;
            }
            // Adapter 重新扫描后，运行表里的 handle 仍指向旧 SDK/client；能安全 stop 的才立即用新 adapter 重启。
            ChannelStreamStatus stoppedStatus = stop(channel);
            statuses.add(stoppedStatus);
            if ("stopped".equals(stoppedStatus.status())) {
                stopped++;
                ChannelStreamStatus startedStatus = start(channel);
                statuses.add(startedStatus);
                if ("running".equals(startedStatus.status())) {
                    restarted++;
                } else if ("unsupported".equals(startedStatus.status())) {
                    unsupported++;
                } else if ("failed".equals(startedStatus.status())) {
                    failed++;
                }
                continue;
            }
            if ("unsupported".equals(stoppedStatus.status())) {
                unsupported++;
            } else if ("failed".equals(stoppedStatus.status())) {
                failed++;
            }
        }
        return new ChannelStreamReloadResult(runningIds.size(), restarted, stopped, unsupported, failed, List.copyOf(statuses));
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

    private ChannelStreamStatus removeMissingChannelStream(String channelId) {
        ChannelStreamHandle handle = runningClients.remove(channelId);
        if (handle != null && handle.stopper() != null) {
            try {
                handle.stopper().stop();
            } catch (Exception e) {
                return ChannelStreamStatus.failed(channelId, "", handle.mode(), safeError("Channel 已删除，停止旧 Stream 失败", e));
            }
        }
        return ChannelStreamStatus.failed(channelId, "", handle == null ? "" : handle.mode(), "Channel 配置不存在，已从运行表移除。");
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
