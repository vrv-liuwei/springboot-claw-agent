package com.github.clawagent.toolkit.filesystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置文件系统工具配置。
 * 该配置不依赖 Spring，starter 会把 application.yml 映射后的值转换进来。
 */
public class FilesystemToolkitProperties {
    private boolean enabled = true;
    private boolean readonly = true;
    private List<String> allowedRoots = new ArrayList<>(List.of("."));
    private String defaultCwd = ".";
    private List<String> blockedPatterns = new ArrayList<>(List.of("**/.git/**", "**/*.key", "**/*.pem", "**/.env"));
    private List<String> ignoredPatterns = new ArrayList<>(List.of(
            "**/.git/**",
            ".git/**",
            "**/.clawagent/**",
            ".clawagent/**",
            "**/node_modules/**",
            "node_modules/**",
            "**/target/**",
            "target/**",
            "**/build/**",
            "build/**",
            "**/dist/**",
            "dist/**",
            "**/.idea/**",
            ".idea/**",
            "**/.vscode/**",
            ".vscode/**"
    ));
    private long maxReadBytes = 1024 * 1024;
    private int maxSearchResults = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        this.readonly = readonly;
    }

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

    public List<String> getBlockedPatterns() {
        return blockedPatterns;
    }

    public void setBlockedPatterns(List<String> blockedPatterns) {
        this.blockedPatterns = blockedPatterns == null ? new ArrayList<>() : new ArrayList<>(blockedPatterns);
    }

    public List<String> getIgnoredPatterns() {
        return ignoredPatterns;
    }

    public void setIgnoredPatterns(List<String> ignoredPatterns) {
        this.ignoredPatterns = ignoredPatterns == null ? new ArrayList<>() : new ArrayList<>(ignoredPatterns);
    }

    public long getMaxReadBytes() {
        return maxReadBytes;
    }

    public void setMaxReadBytes(long maxReadBytes) {
        this.maxReadBytes = maxReadBytes;
    }

    public int getMaxSearchResults() {
        return maxSearchResults;
    }

    public void setMaxSearchResults(int maxSearchResults) {
        this.maxSearchResults = maxSearchResults;
    }

    public static FilesystemToolkitProperties fromEnv(Map<String, String> env) {
        FilesystemToolkitProperties properties = new FilesystemToolkitProperties();
        if (env == null || env.isEmpty()) {
            return properties;
        }
        // env key 统一转大写，兼容 YAML 中使用 allowed_roots / ALLOWED_ROOTS 两种写法。
        Map<String, String> normalized = env.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_'),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));
        properties.setReadonly(booleanValue(normalized.get("READONLY"), properties.isReadonly()));
        properties.setAllowedRoots(listValue(normalized.get("ALLOWED_ROOTS"), properties.getAllowedRoots()));
        properties.setDefaultCwd(stringValue(normalized.get("DEFAULT_CWD"), properties.getDefaultCwd()));
        properties.setBlockedPatterns(listValue(normalized.get("BLOCKED_PATTERNS"), properties.getBlockedPatterns()));
        properties.setIgnoredPatterns(listValue(normalized.get("IGNORED_PATTERNS"), properties.getIgnoredPatterns()));
        properties.setMaxReadBytes(longValue(normalized.get("MAX_READ_BYTES"), properties.getMaxReadBytes()));
        properties.setMaxSearchResults(intValue(normalized.get("MAX_SEARCH_RESULTS"), properties.getMaxSearchResults()));
        return properties;
    }

    private static boolean booleanValue(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static long longValue(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private static int intValue(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static String stringValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static List<String> listValue(String value, List<String> defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        // 允许用分号或逗号分隔多个值；Windows 路径中的盘符冒号不会被误拆。
        return java.util.Arrays.stream(value.split("[;,]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
