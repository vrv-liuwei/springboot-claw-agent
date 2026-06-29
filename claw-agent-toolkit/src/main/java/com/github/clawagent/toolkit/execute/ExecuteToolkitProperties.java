package com.github.clawagent.toolkit.execute;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 本机命令执行工具配置。
 * execute 是高危能力，默认由 application.yml 禁用，并且运行时还会经过 ToolExecutionGuard。
 */
public class ExecuteToolkitProperties {
    private List<String> allowedRoots = new ArrayList<>(List.of("."));
    private String defaultCwd = ".";
    private long timeoutMs = 30000;
    private long processWaitMs = 5000;
    private int maxOutputChars = 20000;
    private List<String> sensitivePathPatterns = new ArrayList<>(List.of(
            "**/.env",
            ".env",
            "**/.env.*",
            ".env.*",
            "**/*.key",
            "**/*.pem",
            "**/*.p12",
            "**/*.pfx",
            "**/.ssh/**",
            ".ssh/**",
            "**/.git/**",
            ".git/**"
    ));

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots == null ? new ArrayList<>() : new ArrayList<>(allowedRoots);
    }

    public String getDefaultCwd() {
        return defaultCwd;
    }

    public void setDefaultCwd(String defaultCwd) {
        this.defaultCwd = defaultCwd == null || defaultCwd.isBlank() ? "." : defaultCwd.trim();
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getProcessWaitMs() {
        return processWaitMs;
    }

    public void setProcessWaitMs(long processWaitMs) {
        this.processWaitMs = processWaitMs <= 0 ? 5000 : processWaitMs;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public List<String> getSensitivePathPatterns() {
        return sensitivePathPatterns;
    }

    public void setSensitivePathPatterns(List<String> sensitivePathPatterns) {
        this.sensitivePathPatterns = sensitivePathPatterns == null ? new ArrayList<>() : new ArrayList<>(sensitivePathPatterns);
    }

    public List<Path> allowedRootPaths() {
        return allowedRoots.stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    public static ExecuteToolkitProperties fromEnv(Map<String, String> env) {
        ExecuteToolkitProperties properties = new ExecuteToolkitProperties();
        if (env == null || env.isEmpty()) {
            return properties;
        }
        Map<String, String> normalized = env.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_'),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));
        properties.setAllowedRoots(listValue(normalized.get("ALLOWED_ROOTS"), properties.getAllowedRoots()));
        properties.setDefaultCwd(stringValue(normalized.get("DEFAULT_CWD"), properties.getDefaultCwd()));
        properties.setTimeoutMs(longValue(normalized.get("TIMEOUT_MS"), properties.getTimeoutMs()));
        properties.setProcessWaitMs(longValue(normalized.get("PROCESS_WAIT_MS"), properties.getProcessWaitMs()));
        properties.setMaxOutputChars(intValue(normalized.get("MAX_OUTPUT_CHARS"), properties.getMaxOutputChars()));
        properties.setSensitivePathPatterns(listValue(normalized.get("SENSITIVE_PATH_PATTERNS"), properties.getSensitivePathPatterns()));
        return properties;
    }

    private static List<String> listValue(String value, List<String> defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(value.split("[;,]")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static long longValue(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private static String stringValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intValue(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }
}
