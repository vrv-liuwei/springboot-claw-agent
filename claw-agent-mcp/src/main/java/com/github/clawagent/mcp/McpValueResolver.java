package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置占位符解析器。
 * 支持在 url、headers、env 中使用 ${ENV_NAME}，避免把真实密钥写死在 mcp.json。
 */
final class McpValueResolver {
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)}");

    private McpValueResolver() {
    }

    static Map<String, String> resolveMap(Map<String, String> values) {
        return resolveMap(values, Map.of());
    }

    static Map<String, String> resolveMap(Map<String, String> values, Map<String, String> localVariables) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            return resolved;
        }
        values.forEach((key, value) -> resolved.put(key, resolve(value, localVariables)));
        return resolved;
    }

    static String resolve(String value) {
        return resolve(value, Map.of());
    }

    static String resolve(String value, Map<String, String> localVariables) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = lookup(envName, localVariables);
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(envValue));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String lookup(String envName, Map<String, String> localVariables) {
        String localValue = localVariables == null ? "" : localVariables.getOrDefault(envName, "");
        if (localValue != null && !localValue.isBlank()) {
            return resolve(localValue, Map.of());
        }
        return System.getenv().getOrDefault(envName, "");
    }
}
