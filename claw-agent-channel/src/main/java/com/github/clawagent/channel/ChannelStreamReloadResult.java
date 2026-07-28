package com.github.clawagent.channel;

import java.util.List;

/**
 * Adapter 重新加载后，当前运行中 Stream 的热切换结果。
 * 这里只记录状态摘要，不携带平台 SDK client 或任何密钥。
 */
public record ChannelStreamReloadResult(
        int runningCount,
        int restartedCount,
        int stoppedCount,
        int unsupportedCount,
        int failedCount,
        List<ChannelStreamStatus> statuses
) {
    public static ChannelStreamReloadResult empty() {
        return new ChannelStreamReloadResult(0, 0, 0, 0, 0, List.of());
    }
}
