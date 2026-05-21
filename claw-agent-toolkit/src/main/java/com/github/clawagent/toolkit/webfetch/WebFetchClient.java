package com.github.clawagent.toolkit.webfetch;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 内置网页抓取客户端。
 * 这里用 Hutool HttpRequest 做轻量 HTTP 请求，并集中处理超时、大小限制和 SSRF 防护。
 */
public class WebFetchClient {
    private static final int DEFAULT_TIMEOUT_MS = (int) Duration.ofSeconds(30).toMillis();
    private static final int DEFAULT_MAX_BYTES = 10*1024 * 1024;

    public WebFetchResponse fetch(String url, Map<String, String> headers, int timeoutMs, int maxBytes) {
        URI uri = validatePublicHttpUrl(url);
        int effectiveTimeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        int effectiveMaxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;

        // Hutool 的 HttpRequest 使用链式 API，适合这里做少量请求参数装配。
        HttpRequest request = HttpRequest.get(uri.toString())
                .timeout(effectiveTimeout)
                .setFollowRedirects(true)
                .header(Header.USER_AGENT, "ClawAgent/0.1 web-fetch");
        headers.forEach(request::header);

        try (HttpResponse response = request.execute()) {
            int status = response.getStatus();
            byte[] bodyBytes = response.bodyBytes();
            if (bodyBytes.length > effectiveMaxBytes) {
                throw new IllegalStateException("响应内容超过限制 maxBytes=" + effectiveMaxBytes + " actualBytes=" + bodyBytes.length);
            }
            String body = new String(bodyBytes, CharsetUtil.CHARSET_UTF_8);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().forEach((key, values) -> responseHeaders.put(key, String.join(",", values)));
            if (status < HttpStatus.HTTP_OK || status >= HttpStatus.HTTP_MULT_CHOICE) {
                throw new IllegalStateException("HTTP 请求失败 status=" + status + " body=" + abbreviate(body, 500));
            }
            return new WebFetchResponse(uri.toString(), status, response.header(Header.CONTENT_TYPE), body, responseHeaders);
        }
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
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()) {
                    throw new IllegalArgumentException("出于安全原因，web-fetch 默认禁止访问内网地址：" + host);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 URL host：" + host, e);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
