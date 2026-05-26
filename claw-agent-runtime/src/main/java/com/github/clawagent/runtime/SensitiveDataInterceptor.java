package com.github.clawagent.runtime;

import com.github.clawagent.spi.AgentRuntimeInterceptor;
import com.github.clawagent.spi.AgentRuntimeInterceptorContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认敏感数据脱敏拦截器。
 * 作为 Runtime Interceptor 链的一环运行，避免 Runtime 主流程硬编码具体脱敏逻辑。
 */
public final class SensitiveDataInterceptor implements AgentRuntimeInterceptor {
    private final boolean enabled;
    private final int order;
    private final String replacement;
    private final List<String> sensitiveKeys;
    private final List<Pattern> valuePatterns;

    public SensitiveDataInterceptor(SanitizationOptions options) {
        SanitizationOptions config = options == null ? SanitizationOptions.defaults() : options;
        this.enabled = config.enabled();
        this.order = config.order();
        this.replacement = config.replacement();
        this.sensitiveKeys = config.sensitiveKeys().stream()
                .map(item -> item == null ? "" : item.toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .toList();
        this.valuePatterns = config.valuePatterns().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(Pattern::compile)
                .toList();
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Map<String, String> beforeEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        if (!enabled) {
            return details;
        }
        return sanitize(details);
    }

    @Override
    public Map<String, String> beforeStreamEvent(AgentRuntimeInterceptorContext context, Map<String, String> details) {
        if (!enabled) {
            return details;
        }
        return sanitize(details);
    }

    @Override
    public String beforeLogValue(AgentRuntimeInterceptorContext context, String key, String value) {
        if (!enabled) {
            return value;
        }
        return sanitizeValue(key, value);
    }

    private Map<String, String> sanitize(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        details.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    private String sanitizeValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        if (isSensitiveKey(key)) {
            return replacement;
        }
        String sanitized = value;
        for (Pattern pattern : valuePatterns) {
            sanitized = replaceSensitiveValue(pattern, sanitized);
        }
        return sanitized;
    }

    private String replaceSensitiveValue(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            // 带前缀捕获组的规则只替换敏感值本身，尽量保留 JSON/参数结构方便排查。
            String replacementValue = matcher.groupCount() >= 2
                    ? Matcher.quoteReplacement(matcher.group(1) + replacement)
                    : Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(buffer, replacementValue);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        for (String sensitiveKey : sensitiveKeys) {
            if (normalized.equals(sensitiveKey) || normalized.contains(sensitiveKey)) {
                return true;
            }
        }
        return false;
    }
}
