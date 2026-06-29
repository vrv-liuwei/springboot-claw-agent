package com.github.clawagent.server.security;

import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.service.ApiTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * API Token 鉴权拦截器。
 * 仅在 clawagent.auth.api-token-required=true 时生效，默认不影响本地单用户 WebUI。
 */
public class ApiTokenAuthInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";

    private final ServerAuthProperties properties;
    private final ApiTokenService apiTokenService;

    public ApiTokenAuthInterceptor(ServerAuthProperties properties, ApiTokenService apiTokenService) {
        this.properties = properties;
        this.apiTokenService = apiTokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!properties.isApiTokenRequired() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = resolveToken(request);
        if (apiTokenService.verifyAndTouch(token, request.getMethod(), request.getRequestURI())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"invalid_api_token\"}");
        return false;
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        String token = request.getHeader("X-ClawAgent-Token");
        return token == null ? "" : token.trim();
    }
}
