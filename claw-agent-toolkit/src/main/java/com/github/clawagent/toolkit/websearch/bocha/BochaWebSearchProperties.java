package com.github.clawagent.toolkit.websearch.bocha;

import java.util.Map;

/**
 * 博查搜索 Provider 专用配置。
 * 字段全部使用 BOCHA_* 前缀，后续新增 Bing/SearXNG 时各自维护独立配置实体。
 */
public class BochaWebSearchProperties {
    private String apiKey = "";
    private String endpoint = "https://api.bochaai.com/v1/web-search";
    private int count = 8;
    private String freshness = "noLimit";
    private boolean summary = true;
    private int timeoutMs = 60_000;
    private int maxOutputChars = 12_000;

    public static BochaWebSearchProperties fromEnv(Map<String, String> env) {
        BochaWebSearchProperties properties = new BochaWebSearchProperties();
        if (env == null) {
            return properties;
        }
        properties.setApiKey(value(env, "BOCHA_API_KEY", properties.getApiKey()));
        properties.setEndpoint(value(env, "BOCHA_ENDPOINT", properties.getEndpoint()));
        properties.setCount(intValue(env, "BOCHA_COUNT", properties.getCount()));
        properties.setFreshness(value(env, "BOCHA_FRESHNESS", properties.getFreshness()));
        properties.setSummary(booleanValue(env, "BOCHA_SUMMARY", properties.isSummary()));
        properties.setTimeoutMs(intValue(env, "BOCHA_TIMEOUT_MS", properties.getTimeoutMs()));
        properties.setMaxOutputChars(intValue(env, "BOCHA_MAX_OUTPUT_CHARS", properties.getMaxOutputChars()));
        return properties;
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intValue(Map<String, String> env, String key, int fallback) {
        try {
            String value = env.get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(Map<String, String> env, String key, boolean fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null || endpoint.isBlank() ? "https://api.bochaai.com/v1/web-search" : endpoint.trim();
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count <= 0 ? 8 : count;
    }

    public String getFreshness() {
        return freshness;
    }

    public void setFreshness(String freshness) {
        this.freshness = freshness == null || freshness.isBlank() ? "noLimit" : freshness.trim();
    }

    public boolean isSummary() {
        return summary;
    }

    public void setSummary(boolean summary) {
        this.summary = summary;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs <= 0 ? 60_000 : timeoutMs;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars <= 0 ? 12_000 : maxOutputChars;
    }
}
