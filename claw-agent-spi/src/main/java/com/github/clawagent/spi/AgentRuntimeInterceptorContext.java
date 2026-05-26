package com.github.clawagent.spi;

import com.github.clawagent.core.AgentTask;

/**
 * Runtime 拦截上下文。
 * channel 表示当前拦截通道，例如 event、stream、log；type 表示事件或日志类型。
 */
public record AgentRuntimeInterceptorContext(
        String channel,
        String type,
        String message,
        AgentTask task) {
}
