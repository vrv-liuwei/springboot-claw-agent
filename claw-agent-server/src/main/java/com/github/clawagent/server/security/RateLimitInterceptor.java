package com.github.clawagent.server.security;

import com.github.clawagent.server.config.ServerRateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP API 固定窗口限流器。
 * 先控制入口请求频率，避免 Channel 回调、外部 API Token 或页面轮询把本地 Agent 压垮。
 */
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final String DEFAULT_RULE = "default";
    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final ServerRateLimitProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong();
    private final Clock clock;

    public RateLimitInterceptor(ServerRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RateLimitInterceptor(ServerRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalizePath(request.getRequestURI());
        if (!matchesAny(properties.getProtectedPathPatterns(), path) || matchesAny(properties.getExcludedPathPatterns(), path)) {
            return true;
        }

        RateLimitPolicy policy = resolvePolicy(request, path);
        if (!policy.enabled()) {
            return true;
        }
        long now = clock.millis();
        cleanupExpiredCounters(now);
        WindowCounter counter = counters.computeIfAbsent(policy.bucketKey(), ignored -> new WindowCounter());
        RateLimitDecision decision = counter.tryAcquire(policy, now);
        if (decision.allowed()) {
            response.setHeader("X-ClawAgent-RateLimit-Limit", String.valueOf(policy.limit()));
            response.setHeader("X-ClawAgent-RateLimit-Remaining", String.valueOf(decision.remaining()));
            return true;
        }
        writeLimitedResponse(response, policy, decision);
        return false;
    }

    private RateLimitPolicy resolvePolicy(HttpServletRequest request, String path) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        ServerRateLimitProperties.Rule matched = properties.getRules().stream()
                .filter(rule -> methodMatches(rule, method) && pathMatches(rule, path))
                .findFirst()
                .orElse(null);
        String ruleName = matched == null || matched.getName().isBlank() ? DEFAULT_RULE : matched.getName();
        int limit = matched != null && matched.getLimit() > 0 ? matched.getLimit() : properties.getDefaultLimit();
        int windowSeconds = matched != null && matched.getWindowSeconds() > 0
                ? matched.getWindowSeconds()
                : properties.getDefaultWindowSeconds();
        String scope = matched == null || matched.getPathPatterns().isEmpty()
                ? path
                : matched.getPathPatterns().get(0);
        String identity = resolveIdentity(request);
        return new RateLimitPolicy(ruleName, identity + "|" + method + "|" + scope, limit, windowSeconds);
    }

    private boolean methodMatches(ServerRateLimitProperties.Rule rule, String method) {
        if (rule.getMethods().isEmpty()) {
            return true;
        }
        return rule.getMethods().stream()
                .map(item -> item == null ? "" : item.trim().toUpperCase(Locale.ROOT))
                .anyMatch(item -> "*".equals(item) || item.equals(method));
    }

    private boolean pathMatches(ServerRateLimitProperties.Rule rule, String path) {
        return rule.getPathPatterns().isEmpty() || matchesAny(rule.getPathPatterns(), path);
    }

    private boolean matchesAny(Iterable<String> patterns, String path) {
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && pathMatcher.match(pattern.trim(), path)) {
                return true;
            }
        }
        return false;
    }

    private String resolveIdentity(HttpServletRequest request) {
        Object tokenId = request.getAttribute(ApiTokenAuthInterceptor.ATTR_TOKEN_ID);
        if (tokenId != null && !String.valueOf(tokenId).isBlank()) {
            return "token:" + tokenId;
        }
        Object userId = request.getAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID);
        if (userId != null && !String.valueOf(userId).isBlank()) {
            return "user:" + userId;
        }
        Object deviceId = request.getAttribute(ApiTokenAuthInterceptor.ATTR_DEVICE_ID);
        if (deviceId != null && !String.valueOf(deviceId).isBlank()) {
            return "device:" + deviceId;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // 只取第一段代理链，避免同一客户端因为代理追加顺序不同绕过限流桶。
            return "ip:" + forwardedFor.split(",", 2)[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void cleanupExpiredCounters(long now) {
        long last = lastCleanupAt.get();
        if (now - last < CLEANUP_INTERVAL_MS || !lastCleanupAt.compareAndSet(last, now)) {
            return;
        }
        counters.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void writeLimitedResponse(HttpServletResponse response, RateLimitPolicy policy, RateLimitDecision decision) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setHeader("X-ClawAgent-RateLimit-Limit", String.valueOf(policy.limit()));
        response.setHeader("X-ClawAgent-RateLimit-Remaining", "0");
        response.getWriter().write("{\"error\":\"rate_limited\",\"rule\":\""
                + escapeJson(policy.ruleName()) + "\",\"retryAfterSeconds\":"
                + decision.retryAfterSeconds() + "}");
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record RateLimitPolicy(String ruleName, String bucketKey, int limit, int windowSeconds) {
        boolean enabled() {
            return limit > 0 && windowSeconds > 0;
        }

        long windowMillis() {
            return Math.max(1L, windowSeconds) * 1000L;
        }
    }

    private record RateLimitDecision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private static final class WindowCounter {
        private long windowStartedAt;
        private int count;

        synchronized RateLimitDecision tryAcquire(RateLimitPolicy policy, long now) {
            long windowMillis = policy.windowMillis();
            if (windowStartedAt <= 0 || now - windowStartedAt >= windowMillis) {
                windowStartedAt = now;
                count = 0;
            }
            if (count >= policy.limit()) {
                long retryAfterMs = Math.max(1L, windowMillis - (now - windowStartedAt));
                return new RateLimitDecision(false, 0, (retryAfterMs + 999L) / 1000L);
            }
            count++;
            return new RateLimitDecision(true, Math.max(0, policy.limit() - count), 0);
        }

        synchronized boolean isExpired(long now) {
            return windowStartedAt > 0 && now - windowStartedAt > CLEANUP_INTERVAL_MS * 2;
        }
    }
}
