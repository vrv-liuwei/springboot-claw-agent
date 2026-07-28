package com.github.clawagent.server.security;

import com.github.clawagent.server.config.ServerRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RateLimitInterceptorTest {
    @Test
    void bypassesWhenRateLimitDisabled() throws Exception {
        ServerRateLimitProperties properties = new ServerRateLimitProperties();
        properties.setEnabled(false);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(properties, fixedClock());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("POST", "/api/v1/tasks", "10.0.0.1"), response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void limitsRequestsByAuthenticatedIdentity() throws Exception {
        ServerRateLimitProperties properties = enabledProperties(2, 60);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(properties, fixedClock());
        MockHttpServletRequest first = request("POST", "/api/v1/tasks", "10.0.0.1");
        first.setAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID, "u-1");
        MockHttpServletRequest second = request("POST", "/api/v1/tasks", "10.0.0.9");
        second.setAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID, "u-1");
        MockHttpServletRequest third = request("POST", "/api/v1/tasks", "10.0.0.8");
        third.setAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID, "u-1");
        MockHttpServletResponse limited = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(second, new MockHttpServletResponse(), new Object()));
        boolean allowed = interceptor.preHandle(third, limited, new Object());

        assertFalse(allowed);
        assertEquals(429, limited.getStatus());
        assertEquals("{\"error\":\"rate_limited\",\"rule\":\"default\",\"retryAfterSeconds\":60}",
                limited.getContentAsString());
    }

    @Test
    void methodSpecificRuleDoesNotLimitReadRequests() throws Exception {
        ServerRateLimitProperties properties = enabledProperties(100, 60);
        ServerRateLimitProperties.Rule writeRule = new ServerRateLimitProperties.Rule();
        writeRule.setName("task-write");
        writeRule.setLimit(1);
        writeRule.setWindowSeconds(60);
        writeRule.setMethods(List.of("POST"));
        writeRule.setPathPatterns(List.of("/api/v1/tasks/**"));
        properties.setRules(List.of(writeRule));
        RateLimitInterceptor interceptor = new RateLimitInterceptor(properties, fixedClock());

        assertTrue(interceptor.preHandle(request("POST", "/api/v1/tasks", "10.0.0.1"),
                new MockHttpServletResponse(), new Object()));
        MockHttpServletResponse writeLimited = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/v1/tasks", "10.0.0.1"),
                writeLimited, new Object()));

        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("GET", "/api/v1/tasks", "10.0.0.1"),
                readResponse, new Object()));
        assertEquals(200, readResponse.getStatus());
    }

    @Test
    void excludedPathBypassesLimiter() throws Exception {
        ServerRateLimitProperties properties = enabledProperties(1, 60);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(properties, fixedClock());

        assertTrue(interceptor.preHandle(request("GET", "/api/v1/health", "10.0.0.1"),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("GET", "/api/v1/health", "10.0.0.1"),
                new MockHttpServletResponse(), new Object()));
    }

    private ServerRateLimitProperties enabledProperties(int limit, int windowSeconds) {
        ServerRateLimitProperties properties = new ServerRateLimitProperties();
        properties.setEnabled(true);
        properties.setDefaultLimit(limit);
        properties.setDefaultWindowSeconds(windowSeconds);
        return properties;
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);
    }

    private MockHttpServletRequest request(String method, String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
