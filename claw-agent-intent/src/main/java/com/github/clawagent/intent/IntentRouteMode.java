package com.github.clawagent.intent;

/**
 * 意图命中后的路由方式。
 * <p>
 * HANDLER 表示由系统处理器直接执行；MODEL 表示只补充上下文后继续让模型回答。
 */
public enum IntentRouteMode {
    HANDLER,
    MODEL;

    /**
     * 从 YAML 字符串解析路由方式，兼容 runtime/agent 这类旧表达。
     */
    public static IntentRouteMode from(String value) {
        if (value == null || value.isBlank()) {
            return HANDLER;
        }
        return switch (value.trim().toLowerCase()) {
            case "model", "runtime", "agent" -> MODEL;
            default -> HANDLER;
        };
    }
}
