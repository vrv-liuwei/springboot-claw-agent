package com.github.clawagent.toolkit.websearch;

import com.github.clawagent.spi.WebSearchProvider;
import com.github.clawagent.toolkit.websearch.bocha.BochaWebSearchProperties;
import com.github.clawagent.toolkit.websearch.bocha.BochaWebSearchProvider;

import java.util.Locale;
import java.util.Map;

/**
 * WebSearchProvider 工厂。
 * 选择 Provider 只看 PROVIDER 字段，厂商配置由各自 Properties 解析，避免把不同 API 参数揉成一套通用配置。
 */
public final class WebSearchProviderFactory {
    private WebSearchProviderFactory() {
    }

    public static WebSearchProvider create(Map<String, String> env) {
        String provider = value(env, "PROVIDER", "bocha").toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "bocha" -> new BochaWebSearchProvider(BochaWebSearchProperties.fromEnv(env));
            default -> throw new IllegalArgumentException("不支持的 web-search provider：" + provider);
        };
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        if (env == null) {
            return fallback;
        }
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
