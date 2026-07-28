package com.github.clawagent.intent;

import java.util.Map;

/**
 * 意图 handler 执行结果。
 * <p>
 * handled=true 表示 handler 已生成最终回复；handled=false 表示只补充 metadata 后继续进入模型。
 */
public record IntentHandlerResult(
        boolean handled,
        String answer,
        Map<String, String> metadata
) {
    public IntentHandlerResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 创建一个直接回复用户的处理结果。
     */
    public static IntentHandlerResult handled(String answer) {
        return new IntentHandlerResult(true, answer, Map.of());
    }

    /**
     * 创建一个继续交给 AgentRuntime 的处理结果。
     */
    public static IntentHandlerResult passThrough(Map<String, String> metadata) {
        return new IntentHandlerResult(false, "", metadata);
    }
}
