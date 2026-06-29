package com.github.clawagent.spi;

import com.github.clawagent.core.AgentRequest;

import java.util.Map;

/**
 * AgentRuntime 拦截器 SPI。
 * 用于扩展运行时横切能力，例如脱敏、审计字段规范化、输出截断和企业合规过滤。
 */
public interface AgentRuntimeInterceptor {
    /**
     * 拦截器顺序，数值越小越先执行。
     */
    default int order() {
        return 0;
    }

    /**
     * AgentRequest 创建任务前处理入口。
     */
    default AgentRequest beforeRequest(AgentRequest request) {
        return request;
    }

    /**
     * 持久化 AgentEvent 前处理事件详情。
     */
    default Map<String, String> beforeEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        return details;
    }

    /**
     * 推送 SSE/流式事件前处理事件详情。
     */
    default Map<String, String> beforeStreamEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        return details;
    }

    /**
     * 写入 Runtime 日志前处理单个文本值。
     */
    default String beforeLogValue(AgentRuntimeInterceptorContext context, String key, String value) {
        return value;
    }

    /**
     * AgentEvent 持久化完成后回调。
     * 适合扩展审计同步、指标统计等后置动作；默认不做处理。
     */
    default void afterEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
    }
}
