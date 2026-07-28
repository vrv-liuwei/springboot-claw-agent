package com.github.clawagent.server.security;

import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.dto.ApiTokenView;
import com.github.clawagent.server.dto.DeviceView;
import com.github.clawagent.server.dto.LocalUserLoginResponse;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.DeviceRegistryService;
import com.github.clawagent.server.service.LocalUserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地主服务鉴权拦截器。
 * 支持 API Token 和本地用户 session 两类凭证，默认关闭，不影响本地单用户调试。
 */
public class ApiTokenAuthInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";
    public static final String ATTR_AUTH_TYPE = "clawagent.auth.type";
    public static final String ATTR_USER_ID = "clawagent.auth.userId";
    public static final String ATTR_USERNAME = "clawagent.auth.username";
    public static final String ATTR_USER_ROLE = "clawagent.auth.userRole";
    public static final String ATTR_TOKEN_ID = "clawagent.auth.tokenId";
    public static final String ATTR_TOKEN_PERMISSION_MODE = "clawagent.auth.tokenPermissionMode";
    public static final String ATTR_TOKEN_APPROVED_TOOL_IDS = "clawagent.auth.tokenApprovedToolIds";
    public static final String ATTR_TOKEN_SCOPES = "clawagent.auth.tokenScopes";
    public static final String ATTR_DEVICE_ID = "clawagent.auth.deviceId";
    public static final String ATTR_DEVICE_NAME = "clawagent.auth.deviceName";
    public static final String ATTR_DEVICE_TYPE = "clawagent.auth.deviceType";
    public static final String ATTR_DEVICE_PERMISSION_MODE = "clawagent.auth.devicePermissionMode";
    public static final String ATTR_DEVICE_APPROVED_TOOL_IDS = "clawagent.auth.deviceApprovedToolIds";
    public static final String ATTR_DEVICE_BOUND_USER_ID = "clawagent.auth.deviceBoundUserId";
    public static final String ATTR_DEVICE_BOUND_USERNAME = "clawagent.auth.deviceBoundUsername";

    private final ServerAuthProperties properties;
    private final ApiTokenService apiTokenService;
    private final LocalUserSessionService localUserSessionService;
    private final DeviceRegistryService deviceRegistryService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ApiTokenAuthInterceptor(ServerAuthProperties properties,
                                   ApiTokenService apiTokenService,
                                   LocalUserSessionService localUserSessionService) {
        this(properties, apiTokenService, localUserSessionService, null);
    }

    public ApiTokenAuthInterceptor(ServerAuthProperties properties,
                                   ApiTokenService apiTokenService,
                                   LocalUserSessionService localUserSessionService,
                                   DeviceRegistryService deviceRegistryService) {
        this.properties = properties;
        this.apiTokenService = apiTokenService;
        this.localUserSessionService = localUserSessionService;
        this.deviceRegistryService = deviceRegistryService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!properties.isRequired() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Credentials credentials = resolveCredentials(request);
        AuthorizationResult result = authorize(credentials, request);
        if (result.allowed()) {
            return true;
        }
        response.setStatus(result.status());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + result.error() + "\"}");
        return false;
    }

    private AuthorizationResult authorize(Credentials credentials, HttpServletRequest request) {
        String token = credentials.token();
        if (token.isBlank()) {
            return AuthorizationResult.unauthorized();
        }
        if ("session".equals(credentials.type())) {
            // 本地用户 session 校验会自动 touch lastUsedAt，便于后续审计空闲会话。
            return localUserSessionService.authenticate(token)
                    .map(response -> {
                        setSessionAttributes(request, response);
                        if (!hasRequiredSessionRole(response, request)) {
                            return AuthorizationResult.forbidden("insufficient_role");
                        }
                        return AuthorizationResult.allow();
                    })
                    .orElseGet(AuthorizationResult::unauthorized);
        }
        if ("device".equals(credentials.type())) {
            if (deviceRegistryService == null) {
                return AuthorizationResult.unauthorized();
            }
            return deviceRegistryService.authenticateSecret(credentials.identity(), token)
                    .map(device -> {
                        if (!hasRequiredDeviceAccess(request)) {
                            return AuthorizationResult.forbidden("insufficient_device_scope");
                        }
                        setDeviceAttributes(request, device);
                        return AuthorizationResult.allow();
                    })
                    .orElseGet(AuthorizationResult::unauthorized);
        }
        // API Token 仍记录最小访问摘要，并把程序身份挂到 request，供任务入口合并权限策略。
        return apiTokenService.authenticateAndTouch(token, request.getMethod(), request.getRequestURI())
                .map(tokenView -> {
                    if (!hasRequiredScope(tokenView, request)) {
                        return AuthorizationResult.forbidden("insufficient_scope");
                    }
                    setTokenAttributes(request, tokenView);
                    return AuthorizationResult.allow();
                })
                .orElseGet(AuthorizationResult::unauthorized);
    }

    private void setSessionAttributes(HttpServletRequest request, LocalUserLoginResponse response) {
        request.setAttribute(ATTR_AUTH_TYPE, "session");
        request.setAttribute(ATTR_USER_ID, response.user().id());
        request.setAttribute(ATTR_USERNAME, response.user().username());
        request.setAttribute(ATTR_USER_ROLE, response.user().role());
    }

    private void setTokenAttributes(HttpServletRequest request, ApiTokenView tokenView) {
        request.setAttribute(ATTR_AUTH_TYPE, "api-token");
        request.setAttribute(ATTR_TOKEN_ID, tokenView.id());
        request.setAttribute(ATTR_USER_ID, tokenView.ownerUserId());
        request.setAttribute(ATTR_USERNAME, tokenView.ownerUsername());
        request.setAttribute(ATTR_TOKEN_PERMISSION_MODE, tokenView.permissionMode());
        request.setAttribute(ATTR_TOKEN_APPROVED_TOOL_IDS, String.join(",", tokenView.approvedToolIds()));
        request.setAttribute(ATTR_TOKEN_SCOPES, String.join(",", tokenView.scopes()));
    }

    private void setDeviceAttributes(HttpServletRequest request, DeviceView device) {
        request.setAttribute(ATTR_AUTH_TYPE, "device");
        request.setAttribute(ATTR_DEVICE_ID, device.id());
        request.setAttribute(ATTR_DEVICE_NAME, device.name());
        request.setAttribute(ATTR_DEVICE_TYPE, device.type());
        request.setAttribute(ATTR_DEVICE_PERMISSION_MODE, device.permissionMode());
        request.setAttribute(ATTR_DEVICE_APPROVED_TOOL_IDS, String.join(",", device.approvedToolIds()));
        request.setAttribute(ATTR_DEVICE_BOUND_USER_ID, device.boundUserId());
        request.setAttribute(ATTR_DEVICE_BOUND_USERNAME, device.boundUsername());
        if (device.boundUserId() != null && !device.boundUserId().isBlank()) {
            // 绑定本地用户的设备请求继续复用 localUserId，后续策略合并会同时命中 user 层和 device 层。
            request.setAttribute(ATTR_USER_ID, device.boundUserId());
            request.setAttribute(ATTR_USERNAME, device.boundUsername());
        }
    }

    private Credentials resolveCredentials(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return new Credentials(guessType(authorization.substring(BEARER_PREFIX.length()).trim()),
                    authorization.substring(BEARER_PREFIX.length()).trim(), "");
        }
        String session = request.getHeader("X-ClawAgent-Session");
        if (session != null && !session.isBlank()) {
            return new Credentials("session", session.trim(), "");
        }
        String deviceId = request.getHeader("X-ClawAgent-Device-Id");
        String deviceSecret = request.getHeader("X-ClawAgent-Device-Secret");
        if (deviceId != null && !deviceId.isBlank() && deviceSecret != null && !deviceSecret.isBlank()) {
            return new Credentials("device", deviceSecret.trim(), deviceId.trim());
        }
        String token = request.getHeader("X-ClawAgent-Token");
        return new Credentials("api-token", token == null ? "" : token.trim(), "");
    }

    private String guessType(String token) {
        return token != null && token.startsWith("clas_") ? "session" : "api-token";
    }

    private boolean hasRequiredSessionRole(LocalUserLoginResponse response, HttpServletRequest request) {
        String role = normalizeRole(response == null || response.user() == null ? "" : response.user().role());
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(request.getRequestURI());
        if (isSelfAuthEndpoint(path)) {
            return true;
        }
        if ("viewer".equals(role)) {
            return methodIsRead(method);
        }
        if (isAdminEndpoint(path)) {
            // Auth/配置/通道/能力管理会改变系统边界，只允许 owner/admin 操作。
            return isAdminRole(role) && (methodIsRead(method) || methodIsWrite(method));
        }
        if (isTaskEndpoint(path)) {
            return isTaskRole(role);
        }
        return methodIsRead(method) || isAdminRole(role);
    }

    private boolean isSelfAuthEndpoint(String path) {
        return "/api/v1/auth/me".equals(path) || "/api/v1/auth/logout".equals(path);
    }

    private boolean isAdminEndpoint(String path) {
        return path.startsWith("/api/v1/auth/users")
                || path.startsWith("/api/v1/auth/tokens")
                || path.startsWith("/api/v1/auth/devices")
                || path.startsWith("/api/v1/config")
                || path.startsWith("/api/v1/channels")
                || path.startsWith("/api/v1/mcp")
                || path.startsWith("/api/v1/skills");
    }

    private boolean isTaskEndpoint(String path) {
        return path.startsWith("/api/v1/tasks")
                || path.startsWith("/api/v1/steps")
                || path.startsWith("/api/v1/agents")
                || path.startsWith("/api/v1/todos")
                || path.startsWith("/api/v1/plans")
                || path.startsWith("/api/v1/sessions");
    }

    private boolean methodIsWrite(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private boolean isAdminRole(String role) {
        return "owner".equals(role) || "admin".equals(role);
    }

    private boolean isTaskRole(String role) {
        return isAdminRole(role) || "operator".equals(role) || "user".equals(role);
    }

    private boolean hasRequiredDeviceAccess(HttpServletRequest request) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(request.getRequestURI());
        if (isAdminEndpoint(path)) {
            return false;
        }
        if (isTaskEndpoint(path) || path.startsWith("/api/v1/attachments")) {
            return true;
        }
        // 设备凭证主要用于本地客户端执行任务；其它普通查询接口只开放只读访问。
        return methodIsRead(method);
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasRequiredScope(ApiTokenView tokenView, HttpServletRequest request) {
        List<String> scopes = tokenView.scopes();
        if (scopes == null || scopes.isEmpty()) {
            // 兼容已有本地 token：未声明 scopes 的旧凭证仍按服务级凭证处理。
            return true;
        }
        Set<String> normalized = scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(scope -> scope.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (normalized.contains("*") || normalized.contains("admin:*")) {
            return true;
        }
        String required = requiredScope(request);
        if (required.isBlank()) {
            return true;
        }
        String domain = required.substring(0, required.indexOf(':'));
        return normalized.contains(required) || normalized.contains(domain + ":*");
    }

    private String requiredScope(HttpServletRequest request) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String path = normalizePath(request.getRequestURI());
        for (Map.Entry<String, List<String>> entry : properties.getScopeMappings().entrySet()) {
            if (matchesAny(entry.getValue(), path)) {
                // scope 域名由配置决定，但读写语义仍由 HTTP 方法统一推导，避免每个接口重复声明。
                return scope(entry.getKey(), method);
            }
        }
        return methodIsRead(method) ? "api:read" : "api:write";
    }

    private boolean matchesAny(List<String> patterns, String path) {
        if (patterns == null) {
            return false;
        }
        return patterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .anyMatch(pattern -> pathMatcher.match(pattern.trim(), path));
    }

    private String scope(String domain, String method) {
        return domain + (methodIsRead(method) ? ":read" : ":write");
    }

    private boolean methodIsRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int queryIndex = path.indexOf('?');
        return (queryIndex >= 0 ? path.substring(0, queryIndex) : path).toLowerCase(Locale.ROOT);
    }

    private record Credentials(String type, String token, String identity) {
    }

    private record AuthorizationResult(boolean allowed, int status, String error) {
        private static AuthorizationResult allow() {
            return new AuthorizationResult(true, 200, "");
        }

        private static AuthorizationResult unauthorized() {
            return new AuthorizationResult(false, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
        }

        private static AuthorizationResult forbidden(String error) {
            return new AuthorizationResult(false, HttpServletResponse.SC_FORBIDDEN, error);
        }
    }
}
