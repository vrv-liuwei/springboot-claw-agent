package com.github.clawagent.channel;

import java.util.List;

/**
 * Channel adapter 重新扫描结果。
 */
public record ChannelAdapterReloadResult(
        int candidateCount,
        int activeCount,
        List<ChannelAdapterDescriptor> adapters
) {
}
