package com.github.clawagent.channel;

/**
 * 平台 Stream/长连接启动后的本地句柄。
 * 通用 manager 只关心运行模式和停止动作，不直接依赖飞书/钉钉 SDK 类型。
 */
public record ChannelStreamHandle(
        String mode,
        Object clientRef,
        Stopper stopper
) {
    @FunctionalInterface
    public interface Stopper {
        void stop() throws Exception;
    }
}
