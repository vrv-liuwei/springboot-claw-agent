package com.github.clawagent.server.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server 层认证开关。
 * 默认关闭强鉴权，避免破坏本地 WebUI；企业接入或个人本地暴露端口时可显式开启。
 */
@ConfigurationProperties(prefix = "clawagent.auth")
public class ServerAuthProperties {
    private boolean required = false;
    /**
     * 兼容旧配置项。新配置优先使用 clawagent.auth.required=true，
     * 旧的 api-token-required=true 仍会开启同一条鉴权链路。
     */
    private boolean apiTokenRequired = false;
    private List<String> protectedPathPatterns = new ArrayList<>(List.of("/api/v1/**"));
    private List<String> excludedPathPatterns = new ArrayList<>(List.of(
            "/api/v1/health",
            "/api/v1/auth/login",
            "/api/v1/auth/setup"
    ));
    /** 本地用户角色到工具权限策略的映射，用于把 owner/admin/operator/viewer/user 固化成可配置模板。 */
    private Map<String, UserRolePolicy> rolePolicies = new LinkedHashMap<>();
    /** API Token scope 域名到接口路径的映射；scope 仍按 domain:read/write 判断。 */
    private Map<String, List<String>> scopeMappings = defaultScopeMappings();

    public boolean isRequired() {
        return required || apiTokenRequired;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

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

    public Map<String, UserRolePolicy> getRolePolicies() {
        return rolePolicies;
    }

    public void setRolePolicies(Map<String, UserRolePolicy> rolePolicies) {
        this.rolePolicies = rolePolicies == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rolePolicies);
    }

    public Map<String, List<String>> getScopeMappings() {
        return scopeMappings;
    }

    public void setScopeMappings(Map<String, List<String>> scopeMappings) {
        this.scopeMappings = normalizeScopeMappings(scopeMappings);
    }

    private Map<String, List<String>> normalizeScopeMappings(Map<String, List<String>> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return defaultScopeMappings();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        mappings.forEach((domain, patterns) -> {
            if (domain == null || domain.isBlank()) {
                return;
            }
            List<String> cleaned = patterns == null ? List.of() : patterns.stream()
                    .filter(pattern -> pattern != null && !pattern.isBlank())
                    .map(String::trim)
                    .toList();
            if (!cleaned.isEmpty()) {
                normalized.put(domain.trim().toLowerCase(), new ArrayList<>(cleaned));
            }
        });
        return normalized.isEmpty() ? defaultScopeMappings() : normalized;
    }

    private static Map<String, List<String>> defaultScopeMappings() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        defaults.put("tasks", List.of("/api/v1/tasks/**", "/api/v1/steps/**", "/api/v1/agents/**", "/api/v1/todos/**"));
        defaults.put("plans", List.of("/api/v1/plans/**"));
        defaults.put("sessions", List.of("/api/v1/sessions/**"));
        defaults.put("auth", List.of("/api/v1/auth/**"));
        defaults.put("channels", List.of("/api/v1/channels/**"));
        defaults.put("config", List.of("/api/v1/config/**"));
        defaults.put("knowledge", List.of("/api/v1/knowledge/**"));
        defaults.put("memory", List.of("/api/v1/memory/**"));
        defaults.put("mcp", List.of("/api/v1/mcp/**"));
        defaults.put("skills", List.of("/api/v1/skills/**"));
        defaults.put("attachments", List.of("/api/v1/attachments/**"));
        return defaults;
    }

    public static class UserRolePolicy {
        /** 是否启用该角色模板；禁用后仅保留配置，不参与任务权限合并。 */
        private boolean enabled = true;
        /** 角色默认工具权限模式，语义和本地用户 metadata 中的 permissionMode 一致。 */
        private String permissionMode = "";
        /** 兼容页面或旧配置命名；permissionMode 为空时才使用。 */
        private String approvalMode = "";
        /** 该角色默认允许的工具白名单；多层策略同时存在时会取交集。 */
        private List<String> approvedToolIds = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) {
            this.permissionMode = permissionMode == null ? "" : permissionMode.trim();
        }
        public String getApprovalMode() { return approvalMode; }
        public void setApprovalMode(String approvalMode) {
            this.approvalMode = approvalMode == null ? "" : approvalMode.trim();
        }
        public List<String> getApprovedToolIds() { return approvedToolIds; }
        public void setApprovedToolIds(List<String> approvedToolIds) {
            this.approvedToolIds = approvedToolIds == null ? new ArrayList<>() : new ArrayList<>(approvedToolIds);
        }
    }
}
