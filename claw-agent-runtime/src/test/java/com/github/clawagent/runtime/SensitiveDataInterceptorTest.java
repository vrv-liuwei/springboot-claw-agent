package com.github.clawagent.runtime;

import com.github.clawagent.spi.AgentRuntimeInterceptorContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SensitiveDataInterceptorTest {

    @Test
    void masksSensitiveEventDetails() {
        SensitiveDataInterceptor interceptor = new SensitiveDataInterceptor(SanitizationOptions.defaults());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("apiKey", "as_sk_123456");
        details.put("arguments", "{token: glpat-secret-token, query: Spring AI}");

        Map<String, String> result = interceptor.beforeEvent(context("tool.started"), details);

        assertEquals("***", result.get("apiKey"));
        assertEquals("{token: ***, query: Spring AI}", result.get("arguments"));
    }

    @Test
    void masksStreamAndLogValues() {
        SensitiveDataInterceptor interceptor = new SensitiveDataInterceptor(SanitizationOptions.defaults());
        Map<String, String> details = Map.of("output", "request api_key sk-test-value");

        Map<String, String> streamResult = interceptor.beforeStreamEvent(context("tool.succeeded"), details);
        String logResult = interceptor.beforeLogValue(context("llm.call"), "requestJson", "{\"Authorization\":\"Bearer sk-test-value\"}");

        assertEquals("request api_key ***", streamResult.get("output"));
        assertEquals("{\"Authorization\":\"Bearer ***\"}", logResult);
    }

    @Test
    void keepsTokenUsageMetrics() {
        SensitiveDataInterceptor interceptor = new SensitiveDataInterceptor(SanitizationOptions.defaults());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("promptTokens", "2732");
        details.put("completionTokens", "122");
        details.put("totalTokens", "2854");
        details.put("apiKey", "sk-test-value");

        Map<String, String> result = interceptor.beforeEvent(context("llm.call"), details);

        assertEquals("2732", result.get("promptTokens"));
        assertEquals("122", result.get("completionTokens"));
        assertEquals("2854", result.get("totalTokens"));
        assertEquals("***", result.get("apiKey"));
    }

    @Test
    void keepsOriginalValuesWhenDisabled() {
        SensitiveDataInterceptor interceptor = new SensitiveDataInterceptor(new SanitizationOptions(false, 0, "***", null, null));
        Map<String, String> details = Map.of("apiKey", "as_sk_123456");

        Map<String, String> result = interceptor.beforeEvent(context("tool.started"), details);

        assertEquals("as_sk_123456", result.get("apiKey"));
    }

    private AgentRuntimeInterceptorContext context(String type) {
        return new AgentRuntimeInterceptorContext("test", type, "测试拦截器", null);
    }
}
