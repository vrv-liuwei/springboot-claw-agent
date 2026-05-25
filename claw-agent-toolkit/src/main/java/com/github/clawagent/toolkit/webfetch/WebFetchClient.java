package com.github.clawagent.toolkit.webfetch;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.HttpStatus;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置网页抓取客户端。
 * 通过 AgentHttpClient 做轻量 HTTP 请求，并集中处理超时、大小限制和 SSRF 防护。
 */
public class WebFetchClient {
    private static final int DEFAULT_TIMEOUT_MS = (int) Duration.ofSeconds(30).toMillis();
    private static final int DEFAULT_MAX_BYTES = 10*1024 * 1024;
    private final WebFetchToolkitProperties properties;

    public WebFetchClient() {
        this(new WebFetchToolkitProperties());
    }

    public WebFetchClient(WebFetchToolkitProperties properties) {
        this.properties = properties == null ? new WebFetchToolkitProperties() : properties;
    }

    public WebFetchResponse fetch(String url, Map<String, String> headers, int timeoutMs, int maxBytes) {
        URI uri = validatePublicHttpUrl(url);
        int effectiveTimeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        int effectiveMaxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;

        AgentHttpResponse response = AgentHttpClient.get(uri.toString(), headers, effectiveTimeout);
        int status = response.statusCode();
        byte[] bodyBytes = response.bodyBytes();
        if (bodyBytes.length > effectiveMaxBytes) {
            throw new IllegalStateException("响应内容超过限制 maxBytes=" + effectiveMaxBytes + " actualBytes=" + bodyBytes.length);
        }
        String body = new String(bodyBytes, CharsetUtil.CHARSET_UTF_8);
        Map<String, String> responseHeaders = new LinkedHashMap<>(response.headers());
        if (status < HttpStatus.HTTP_OK || status >= HttpStatus.HTTP_MULT_CHOICE) {
            throw new IllegalStateException("HTTP 请求失败 status=" + status + " body=" + abbreviate(body, 500));
        }
        return new WebFetchResponse(uri.toString(), status, response.contentType(), body, responseHeaders);
    }

    private URI validatePublicHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("只允许 http/https URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少 host");
        }
        rejectPrivateHost(uri.getHost());
        // 使用 Hutool UrlBuilder 做一次规范化，避免保留奇怪的控制字符。
        return URI.create(UrlBuilder.of(uri.toString()).toString());
    }

    private void rejectPrivateHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()) {
                    throw new IllegalArgumentException("出于安全原因，web-fetch 默认禁止访问本机或链路本地地址：" + host);
                }
                if (address.isSiteLocalAddress() && !isPrivateHostAllowed(host)) {
                    throw new IllegalArgumentException("出于安全原因，web-fetch 默认禁止访问内网地址：" + host
                            + "。如需访问局域网 Git，请配置 clawagent.toolkit.tools.web-fetch.env.ALLOW_PRIVATE_ADDRESSES=true"
                            + " 或 ALLOWED_HOSTS=" + host);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 URL host：" + host, e);
        }
    }

    private boolean isPrivateHostAllowed(String host) {
        // 内网访问必须显式开启：可以全局允许私网地址，也可以只放行某些 Git host。
        return properties.isAllowPrivateAddresses() || properties.isAllowedHost(host);
    }

    private String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
