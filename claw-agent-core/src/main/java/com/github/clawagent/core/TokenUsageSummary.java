package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM Token 用量聚合结果。
 * 当前从 llm.call 事件中汇总，后续可以平滑切换到独立统计表。
 */
public class TokenUsageSummary {
    /** 统计范围类型，例如 session、task。 */
    private final String scopeType;
    /** 统计范围 ID，例如 sessionId 或 taskId。 */
    private final String scopeId;
    /** LLM 调用次数。 */
    private final int callCount;
    /** prompt token 总数。 */
    private final int promptTokens;
    /** completion token 总数。 */
    private final int completionTokens;
    /** prompt + completion token 总数。 */
    private final int totalTokens;
    /** 按模型拆分的 token 统计。 */
    private final Map<String, Breakdown> byModel;
    /** 按调用阶段拆分的 token 统计。 */
    private final Map<String, Breakdown> byPhase;

    /**
     * 创建 token 用量聚合结果。
     *
     * @param scopeType 统计范围类型。
     * @param scopeId 统计范围 ID。
     * @param callCount LLM 调用次数。
     * @param promptTokens prompt token 总数。
     * @param completionTokens completion token 总数。
     * @param totalTokens prompt + completion token 总数。
     * @param byModel 按模型拆分的 token 统计。
     * @param byPhase 按调用阶段拆分的 token 统计。
     */
    public TokenUsageSummary(
            String scopeType,
            String scopeId,
            int callCount,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            Map<String, Breakdown> byModel,
            Map<String, Breakdown> byPhase) {
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.callCount = callCount;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.byModel = byModel == null ? new LinkedHashMap<>() : new LinkedHashMap<>(byModel);
        this.byPhase = byPhase == null ? new LinkedHashMap<>() : new LinkedHashMap<>(byPhase);
    }

    public String scopeType() { return scopeType; }
    public String getScopeType() { return scopeType; }
    public String scopeId() { return scopeId; }
    public String getScopeId() { return scopeId; }
    public int callCount() { return callCount; }
    public int getCallCount() { return callCount; }
    public int promptTokens() { return promptTokens; }
    public int getPromptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int totalTokens() { return totalTokens; }
    public int getTotalTokens() { return totalTokens; }
    public Map<String, Breakdown> byModel() { return byModel; }
    public Map<String, Breakdown> getByModel() { return byModel; }
    public Map<String, Breakdown> byPhase() { return byPhase; }
    public Map<String, Breakdown> getByPhase() { return byPhase; }

    /**
     * 按模型或阶段拆分后的 token 统计。
     */
    public static class Breakdown {
        /** LLM 调用次数。 */
        private int callCount;
        /** prompt token 总数。 */
        private int promptTokens;
        /** completion token 总数。 */
        private int completionTokens;
        /** prompt + completion token 总数。 */
        private int totalTokens;

        /**
         * 累加一次 LLM 调用 token 用量。
         *
         * @param promptTokens 本次调用 prompt token 数。
         * @param completionTokens 本次调用 completion token 数。
         * @param totalTokens 本次调用 token 总数。
         */
        public void add(int promptTokens, int completionTokens, int totalTokens) {
            this.callCount++;
            this.promptTokens += promptTokens;
            this.completionTokens += completionTokens;
            this.totalTokens += totalTokens;
        }

        public int callCount() { return callCount; }
        public int getCallCount() { return callCount; }
        public int promptTokens() { return promptTokens; }
        public int getPromptTokens() { return promptTokens; }
        public int completionTokens() { return completionTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int totalTokens() { return totalTokens; }
        public int getTotalTokens() { return totalTokens; }
    }
}
