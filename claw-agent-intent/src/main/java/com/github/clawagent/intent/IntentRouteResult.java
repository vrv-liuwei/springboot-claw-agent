package com.github.clawagent.intent;

import java.util.Map;

/**
 * 意图路由结果。
 * <p>
 * matched 表示识别到系统意图；handled 表示已经直接回复；passToModel 表示需要带 metadata 继续进入 AgentRuntime。
 */
public record IntentRouteResult(
        boolean matched,
        boolean handled,
        boolean passToModel,
        String answer,
        Map<String, String> metadata,
        String intentId,
        double score
) {
    public IntentRouteResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 未命中任何系统意图，调用方应按普通用户消息进入 AgentRuntime。
     */
    public static IntentRouteResult notMatched() {
        return new IntentRouteResult(false, false, false, "", Map.of(), "", 0);
    }

    /**
     * 意图已经完成处理，调用方直接把 answer 返回给用户。
     */
    public static IntentRouteResult handled(String answer, String intentId, double score) {
        return new IntentRouteResult(true, true, false, answer, Map.of(), intentId, score);
    }

    /**
     * 意图只负责改写 metadata，后续仍由主模型生成最终回答。
     */
    public static IntentRouteResult passToModel(Map<String, String> metadata, String intentId, double score) {
        return new IntentRouteResult(true, false, true, "", metadata, intentId, score);
    }
}
