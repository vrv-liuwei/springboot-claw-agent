package com.github.clawagent.channel;

/**
 * Channel adapter 运行时诊断信息。
 * 管理台用它确认某个 type 当前由内置实现还是外部 jar 实现接管。
 */
public record ChannelAdapterDescriptor(
        String type,
        String className,
        String source,
        String location,
        boolean active
) {
}
