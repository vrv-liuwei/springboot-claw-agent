package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import com.github.clawagent.server.dto.ApiTokenCreateResponse;
import com.github.clawagent.server.dto.ApiTokenView;
import com.github.clawagent.server.dto.AuthSetupView;
import com.github.clawagent.server.dto.LocalUserCurrentResponse;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.LocalUserLoginRequest;
import com.github.clawagent.server.dto.LocalUserLoginResponse;
import com.github.clawagent.server.dto.LocalUserPasswordChangeRequest;
import com.github.clawagent.server.dto.LocalUserPermissionUpdateRequest;
import com.github.clawagent.server.dto.LocalUserSessionView;
import com.github.clawagent.server.dto.LocalUserView;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.LocalUserSessionService;
import com.github.clawagent.server.service.LocalUserService;
import com.github.clawagent.spi.AgentEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AuthController 提供本地主服务的轻量认证配置入口。
 * 当前管理 API Token、本地用户和本地登录会话；默认不启用全局登录拦截，避免影响本地 WebUI 调试。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiTokenService apiTokenService;
    private final LocalUserService localUserService;
    private final LocalUserSessionService localUserSessionService;
    private final AgentEventStore eventStore;

    public AuthController(ApiTokenService apiTokenService,
                          LocalUserService localUserService,
                          LocalUserSessionService localUserSessionService,
                          @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.apiTokenService = apiTokenService;
        this.localUserService = localUserService;
        this.localUserSessionService = localUserSessionService;
        this.eventStore = eventStore;
    }

    @GetMapping("/setup")
    public AuthSetupView setupStatus() {
        return toSetupView();
    }

    @PostMapping("/setup")
    public LocalUserView setupOwner(@RequestBody LocalUserCreateRequest request) {
        try {
            LocalUserView view = localUserService.setupOwner(request);
            recordUserAudit("auth.user.owner_setup", "本地 owner 已初始化", view);
            return view;
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @PostMapping("/login")
    public LocalUserLoginResponse login(@RequestBody LocalUserLoginRequest request) {
        Optional<LocalUserView> user = localUserService.authenticate(
                request == null ? null : request.username(),
                request == null ? null : request.password());
        if (user.isEmpty()) {
            recordAuthAudit("auth.user.login_failed", "本地用户登录失败", Map.of(
                    "username", request == null ? "" : safeString(request.username())));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        LocalUserLoginResponse response = localUserSessionService.create(user.get());
        recordSessionAudit("auth.user.login_succeeded", "本地用户已登录", response);
        return response;
    }

    @GetMapping("/me")
    public LocalUserCurrentResponse currentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-ClawAgent-Session", required = false) String sessionHeader) {
        LocalUserLoginResponse response = localUserSessionService.authenticate(resolveSessionToken(authorization, sessionHeader))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "本地登录会话无效"));
        return new LocalUserCurrentResponse(response.user(), response.session());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-ClawAgent-Session", required = false) String sessionHeader) {
        String sessionToken = resolveSessionToken(authorization, sessionHeader);
        boolean revoked = localUserSessionService.revoke(sessionToken)
                .map(session -> {
                    recordAuthAudit("auth.user.logout", "本地用户已退出", Map.of(
                            "sessionId", session.sessionId(),
                            "userId", session.userId(),
                            "username", session.username(),
                            "tokenPrefix", session.tokenPrefix()));
                    return true;
                })
                .orElse(false);
        return Map.of("success", revoked);
    }

    @GetMapping("/users")
    public List<LocalUserView> users() {
        return localUserService.list();
    }

    @PostMapping("/users")
    public LocalUserView createUser(@RequestBody LocalUserCreateRequest request) {
        LocalUserView view = localUserService.create(request);
        recordUserAudit("auth.user.created", "本地用户已创建", view);
        return view;
    }

    @PostMapping("/users/{userId}/password")
    public LocalUserView changeUserPassword(
            @PathVariable("userId") String userId,
            @RequestBody LocalUserPasswordChangeRequest request) {
        LocalUserView view = localUserService.changePassword(userId, request);
        recordUserAudit("auth.user.password_changed", "本地用户密码已更新", view);
        return view;
    }

    @PostMapping("/users/{userId}/permissions")
    public LocalUserView updateUserPermissions(
            @PathVariable("userId") String userId,
            @RequestBody LocalUserPermissionUpdateRequest request) {
        LocalUserView view = localUserService.updatePermissions(userId, request);
        recordUserAudit("auth.user.permissions_updated", "本地用户权限已更新", view);
        return view;
    }

    @DeleteMapping("/users/{userId}")
    public LocalUserView disableUser(@PathVariable("userId") String userId) {
        LocalUserView view = localUserService.disable(userId);
        recordUserAudit("auth.user.disabled", "本地用户已禁用", view);
        return view;
    }

    @GetMapping("/sessions")
    public List<LocalUserSessionView> sessions() {
        return localUserSessionService.list();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public LocalUserSessionView revokeSession(@PathVariable("sessionId") String sessionId) {
        LocalUserSessionView view = localUserSessionService.revokeBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "本地登录会话不存在或已撤销"));
        recordSessionRevokedAudit("auth.user.session_revoked", "本地用户会话已撤销", view);
        return view;
    }

    @GetMapping("/tokens")
    public List<ApiTokenView> tokens() {
        return apiTokenService.list();
    }

    @PostMapping("/tokens")
    public ApiTokenCreateResponse createToken(@RequestBody ApiTokenCreateRequest request) {
        ApiTokenCreateResponse response = apiTokenService.create(request);
        recordTokenAudit("auth.token.created", "API Token 已创建", response.token(), response.tokenInfo());
        return response;
    }

    @DeleteMapping("/tokens/{tokenId}")
    public ApiTokenView deleteToken(@PathVariable("tokenId") String tokenId) {
        ApiTokenView view = apiTokenService.delete(tokenId);
        recordTokenAudit("auth.token.deleted", "API Token 已删除", null, view);
        return view;
    }

    private void recordTokenAudit(String type, String message, String rawToken, ApiTokenView view) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("tokenId", view.id());
        details.put("name", view.name());
        details.put("status", view.status());
        details.put("tokenPrefix", view.tokenPrefix());
        details.put("ownerUserId", view.ownerUserId() == null ? "" : view.ownerUserId());
        details.put("ownerUsername", view.ownerUsername() == null ? "" : view.ownerUsername());
        details.put("permissionMode", view.permissionMode() == null ? "" : view.permissionMode());
        details.put("scopeCount", String.valueOf(view.scopes() == null ? 0 : view.scopes().size()));
        details.put("metadataCount", String.valueOf(view.metadata() == null ? 0 : view.metadata().size()));
        details.put("tokenReturned", String.valueOf(rawToken != null && !rawToken.isBlank()));
        // 审计只保留 token 前缀和状态，绝不记录 token 明文或 hash。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private AuthSetupView toSetupView() {
        return new AuthSetupView(
                localUserService.isInitialized(),
                localUserService.count(),
                localUserService.ownerExists(),
                localUserService.supportedRoles());
    }

    private void recordUserAudit(String type, String message, LocalUserView view) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("userId", view.id());
        details.put("username", view.username());
        details.put("role", view.role());
        details.put("status", view.status());
        details.put("metadataCount", String.valueOf(view.metadata() == null ? 0 : view.metadata().size()));
        // 用户审计只记录身份摘要，不记录密码、salt 或 hash。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void recordSessionAudit(String type, String message, LocalUserLoginResponse response) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("userId", response.user().id());
        details.put("username", response.user().username());
        details.put("role", response.user().role());
        details.put("sessionId", response.session().sessionId());
        details.put("tokenPrefix", response.session().tokenPrefix());
        details.put("expiresAt", String.valueOf(response.session().expiresAt()));
        // 会话审计只记录 tokenPrefix，不能写入 sessionToken 明文。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void recordSessionRevokedAudit(String type, String message, LocalUserSessionView view) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("sessionId", view.sessionId());
        details.put("userId", view.userId());
        details.put("username", view.username());
        details.put("role", view.role());
        details.put("tokenPrefix", view.tokenPrefix());
        details.put("status", view.status());
        // 管理员撤销会话同样只写 token 前缀，避免审计日志变成凭证泄露源。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void recordAuthAudit(String type, String message, Map<String, String> details) {
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message,
                details == null ? Map.of() : new LinkedHashMap<>(details)));
    }

    private String resolveSessionToken(String authorization, String sessionHeader) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return sessionHeader == null ? "" : sessionHeader.trim();
    }

    private String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}
