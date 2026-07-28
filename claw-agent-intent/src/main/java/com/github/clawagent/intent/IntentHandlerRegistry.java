package com.github.clawagent.intent;

import java.util.Map;
import java.util.Optional;

/**
 * 意图 handler 注册表。
 * <p>
 * Spring Bean 或手工注册的 IntentHandler 会按 handlerId 查找，避免路由层依赖具体实现类。
 */
public class IntentHandlerRegistry {
    private final Map<String, IntentHandler> handlers;

    /**
     * 用 handlerId 到处理器实例的映射构建注册表。
     */
    public IntentHandlerRegistry(Map<String, IntentHandler> handlers) {
        this.handlers = handlers == null ? Map.of() : Map.copyOf(handlers);
    }

    /**
     * 查找指定 handlerId 对应的处理器。
     */
    public Optional<IntentHandler> find(String handlerId) {
        if (handlerId == null || handlerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(handlerId.trim()));
    }
}
