package com.github.clawagent.runtime;

import java.util.List;

/**
 * 运行时脱敏配置，避免 API Key、Token 等敏感值进入持久化事件和前端流式事件。
 */
public record SanitizationOptions(
        boolean enabled,
        int order,
        String replacement,
        List<String> sensitiveKeys,
        List<String> valuePatterns) {
    public SanitizationOptions {
        replacement = replacement == null || replacement.isBlank() ? "***" : replacement;
        sensitiveKeys = sensitiveKeys == null ? defaultSensitiveKeys() : List.copyOf(sensitiveKeys);
        valuePatterns = valuePatterns == null ? defaultValuePatterns() : List.copyOf(valuePatterns);
    }

    public static SanitizationOptions defaults() {
        return new SanitizationOptions(true, 0, "***", defaultSensitiveKeys(), defaultValuePatterns());
    }

    private static List<String> defaultSensitiveKeys() {
        return List.of("api_key", "apikey", "api-key", "authorization", "token", "secret", "password", "key");
    }

    private static List<String> defaultValuePatterns() {
        return List.of(
                "(?i)(api[_-]?key[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(authorization[\"'\\s:=]+Bearer\\s+)([^\"'\\s,}]+)",
                "(?i)(token[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(secret[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(password[\"'\\s:=]+)([^\"'\\s,}]+)",
                "as_sk_[A-Za-z0-9_\\-]+",
                "sk-[A-Za-z0-9_\\-]+",
                "glpat-[A-Za-z0-9_\\-]+"
        );
    }
}
