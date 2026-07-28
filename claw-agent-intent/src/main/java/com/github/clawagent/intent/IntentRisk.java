package com.github.clawagent.intent;

/**
 * 意图或工具动作的风险等级。
 * <p>
 * LOW 可直接执行，MEDIUM/HIGH 会进入 PendingActionService 的确认流程。
 */
public enum IntentRisk {
    LOW,
    MEDIUM,
    HIGH;

    /**
     * 从 YAML 字符串解析风险等级，无法识别时按 LOW 处理。
     */
    public static IntentRisk from(String value) {
        if (value == null || value.isBlank()) {
            return LOW;
        }
        return switch (value.trim().toLowerCase()) {
            case "medium" -> MEDIUM;
            case "high" -> HIGH;
            default -> LOW;
        };
    }

    /**
     * 写入 metadata 时使用小写值，便于日志和前端展示。
     */
    public String metadataValue() {
        return name().toLowerCase();
    }
}
