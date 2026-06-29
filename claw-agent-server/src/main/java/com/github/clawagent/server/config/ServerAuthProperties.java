package com.github.clawagent.server.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server 层认证开关。
 * 默认关闭强鉴权，避免破坏本地 WebUI；企业接入时可显式开启 API Token 拦截。
 */
@ConfigurationProperties(prefix = "clawagent.auth")
public class ServerAuthProperties {
    private boolean apiTokenRequired = false;
    private List<String> protectedPathPatterns = new ArrayList<>(List.of("/api/v1/**"));
    private List<String> excludedPathPatterns = new ArrayList<>(List.of("/api/v1/health"));

    public boolean isApiTokenRequired() {
        return apiTokenRequired;
    }

    public void setApiTokenRequired(boolean apiTokenRequired) {
        this.apiTokenRequired = apiTokenRequired;
    }

    public List<String> getProtectedPathPatterns() {
        return protectedPathPatterns;
    }

    public void setProtectedPathPatterns(List<String> protectedPathPatterns) {
        this.protectedPathPatterns = protectedPathPatterns == null ? new ArrayList<>() : new ArrayList<>(protectedPathPatterns);
    }

    public List<String> getExcludedPathPatterns() {
        return excludedPathPatterns;
    }

    public void setExcludedPathPatterns(List<String> excludedPathPatterns) {
        this.excludedPathPatterns = excludedPathPatterns == null ? new ArrayList<>() : new ArrayList<>(excludedPathPatterns);
    }
}
