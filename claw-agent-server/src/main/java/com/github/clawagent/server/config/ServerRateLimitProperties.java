package com.github.clawagent.server.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server 层 HTTP 限流配置。
 * 默认关闭，避免影响本地开发；对外开放 API 或 Channel 回调时再显式启用。
 */
@ConfigurationProperties(prefix = "clawagent.rate-limit")
public class ServerRateLimitProperties {
    private boolean enabled = false;
    private int defaultLimit = 120;
    private int defaultWindowSeconds = 60;
    private List<String> protectedPathPatterns = new ArrayList<>(List.of("/api/v1/**"));
    private List<String> excludedPathPatterns = new ArrayList<>(List.of("/api/v1/health"));
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public int getDefaultWindowSeconds() { return defaultWindowSeconds; }
    public void setDefaultWindowSeconds(int defaultWindowSeconds) { this.defaultWindowSeconds = defaultWindowSeconds; }
    public List<String> getProtectedPathPatterns() { return protectedPathPatterns; }
    public void setProtectedPathPatterns(List<String> protectedPathPatterns) {
        this.protectedPathPatterns = protectedPathPatterns == null ? new ArrayList<>() : new ArrayList<>(protectedPathPatterns);
    }
    public List<String> getExcludedPathPatterns() { return excludedPathPatterns; }
    public void setExcludedPathPatterns(List<String> excludedPathPatterns) {
        this.excludedPathPatterns = excludedPathPatterns == null ? new ArrayList<>() : new ArrayList<>(excludedPathPatterns);
    }
    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
    }

    public static class Rule {
        private String name = "";
        private int limit = 0;
        private int windowSeconds = 0;
        private List<String> pathPatterns = new ArrayList<>();
        private List<String> methods = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name == null ? "" : name.trim(); }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
        public List<String> getPathPatterns() { return pathPatterns; }
        public void setPathPatterns(List<String> pathPatterns) {
            this.pathPatterns = pathPatterns == null ? new ArrayList<>() : new ArrayList<>(pathPatterns);
        }
        public List<String> getMethods() { return methods; }
        public void setMethods(List<String> methods) {
            this.methods = methods == null ? new ArrayList<>() : new ArrayList<>(methods);
        }
    }
}
