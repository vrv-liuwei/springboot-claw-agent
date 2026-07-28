package com.github.clawagent.intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个系统意图定义。
 * <p>
 * 一条定义来自 system-intents.yml，包含匹配样例、风险等级、路由方式和 handler/metadata。
 */
public record IntentDefinition(
        String id,
        String name,
        IntentRisk risk,
        IntentRouteMode routeMode,
        String handler,
        double threshold,
        Map<String, String> metadata,
        List<String> examples,
        List<String> negativeExamples
) {
    public IntentDefinition {
        risk = risk == null ? IntentRisk.LOW : risk;
        routeMode = routeMode == null ? IntentRouteMode.HANDLER : routeMode;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        examples = examples == null ? List.of() : List.copyOf(examples);
        negativeExamples = negativeExamples == null ? List.of() : List.copyOf(negativeExamples);
    }

    /**
     * 为未单独配置阈值的意图填充全局默认 threshold。
     */
    public IntentDefinition withDefaults(double defaultThreshold) {
        return new IntentDefinition(id, name, risk, routeMode, handler,
                threshold <= 0 ? defaultThreshold : threshold,
                new LinkedHashMap<>(metadata), examples, negativeExamples);
    }
}
