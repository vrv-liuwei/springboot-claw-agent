package com.github.clawagent.intent;

/**
 * 系统意图处理器接口。
 * <p>
 * 每个 handlerId 对应一个业务处理器，例如清理上下文、查看状态、确认计划。
 */
public interface IntentHandler {
    /**
     * 执行当前意图，返回直接回复或继续进入模型所需的 metadata。
     */
    IntentHandlerResult handle(IntentExecutionContext context);
}
