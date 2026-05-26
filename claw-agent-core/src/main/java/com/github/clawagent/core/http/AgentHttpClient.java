package com.github.clawagent.core.http;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 统一 HTTP 客户端。
 * 普通 get/post、下载、上传、图片/二进制请求都从这里走，避免各模块重复维护请求头、超时和 TLS 逻辑。
 */
public final class AgentHttpClient {
    public static final int DEFAULT_TIMEOUT_MS = 60_000;
    public static final String CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;
    private static final SSLSocketFactory TRUST_ALL_SSL_SOCKET_FACTORY = trustAllSslSocketFactory();

    private AgentHttpClient() {
    }

    public static AgentHttpResponse get(String url) {
        return execute(AgentHttpRequest.get(url));
    }

    public static AgentHttpResponse get(String url, Map<String, String> headers, int timeoutMs) {
        return execute(AgentHttpRequest.get(url).headers(headers).timeoutMs(timeoutMs));
    }

    public static AgentHttpResponse postJson(String url, String json, Map<String, String> headers, int timeoutMs) {
        return execute(AgentHttpRequest.post(url)
                .header("Accept", "application/json")
                .headers(headers)
                .timeoutMs(timeoutMs)
                .body(json)
                .contentType("application/json; charset=utf-8"));
    }

    public static AgentHttpResponse postForm(String url, Map<String, Object> form, Map<String, String> headers, int timeoutMs) {
        return execute(AgentHttpRequest.post(url).headers(headers).timeoutMs(timeoutMs).form(form));
    }

    public static AgentHttpResponse upload(String url, String fieldName, Path file, Map<String, Object> form, Map<String, String> headers, int timeoutMs) {
        Map<String, Object> mergedForm = new LinkedHashMap<>();
        if (form != null) {
            mergedForm.putAll(form);
        }
        mergedForm.put(fieldName, file.toFile());
        return postForm(url, mergedForm, headers, timeoutMs);
    }

    public static AgentHttpResponse download(String url, Path target, Map<String, String> headers, int timeoutMs) {
        AgentHttpResponse response = get(url, headers, timeoutMs);
        FileUtil.writeBytes(response.bodyBytes(), target.toFile());
        return response;
    }

    public static byte[] getBytes(String url, Map<String, String> headers, int timeoutMs) {
        return get(url, headers, timeoutMs).bodyBytes();
    }

    public static AgentHttpResponse execute(AgentHttpRequest request) {
        HttpRequest hutoolRequest = build(request);
        try (HttpResponse response = hutoolRequest.execute()) {
            byte[] bodyBytes = response.bodyBytes();
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().forEach((key, values) -> responseHeaders.put(key, String.join(",", values)));
            String contentType = response.header(Header.CONTENT_TYPE);
            String body = new String(bodyBytes, request.charset());
            return new AgentHttpResponse(response.getStatus(), contentType, responseHeaders, bodyBytes, body);
        }
    }

    private static HttpRequest build(AgentHttpRequest request) {
        HttpRequest httpRequest = switch (request.method()) {
            case "POST" -> HttpRequest.post(request.url());
            case "PUT" -> HttpRequest.put(request.url());
            case "DELETE" -> HttpRequest.delete(request.url());
            case "PATCH" -> HttpRequest.of(request.url()).method(Method.PATCH);
            default -> HttpRequest.get(request.url());
        };
        httpRequest.timeout(request.timeoutMs() <= 0 ? DEFAULT_TIMEOUT_MS : request.timeoutMs());
        httpRequest.setFollowRedirects(request.followRedirects());
        applyChromeHeaders(httpRequest);
        if (request.ignoreSsl()) {
            // 内网 Git、私有仓库或自签证书场景可显式忽略证书校验；默认仍保持 JDK/Hutool 正常校验。
            httpRequest.setSSLSocketFactory(TRUST_ALL_SSL_SOCKET_FACTORY);
            httpRequest.setHostnameVerifier(TRUST_ALL_HOSTS);
        }
        request.headers().forEach(httpRequest::header);
        if (request.contentType() != null && !request.contentType().isBlank()) {
            httpRequest.header(Header.CONTENT_TYPE, request.contentType(), true);
        }
        if (!request.form().isEmpty()) {
            request.form().forEach((key, value) -> {
                if (value instanceof Path path) {
                    httpRequest.form(key, path.toFile());
                } else if (value instanceof File file) {
                    httpRequest.form(key, file);
                } else {
                    httpRequest.form(key, value);
                }
            });
        }
        if (request.body() != null) {
            httpRequest.body(request.body());
        }
        return httpRequest;
    }

    private static void applyChromeHeaders(HttpRequest request) {
        request.header("User-Agent", CHROME_USER_AGENT, true);
        request.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8", true);
        request.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8", true);
        request.header("Accept-Encoding", "identity", true);
        request.header("Cache-Control", "no-cache", true);
        request.header("Pragma", "no-cache", true);
        request.header("Upgrade-Insecure-Requests", "1", true);
        request.header("Sec-Fetch-Dest", "document", true);
        request.header("Sec-Fetch-Mode", "navigate", true);
        request.header("Sec-Fetch-Site", "none", true);
        request.header("Sec-Fetch-User", "?1", true);
        request.header("sec-ch-ua", "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"", true);
        request.header("sec-ch-ua-mobile", "?0", true);
        request.header("sec-ch-ua-platform", "\"Windows\"", true);
    }

    private static SSLSocketFactory trustAllSslSocketFactory() {
        try {
            TrustManager[] trustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("初始化忽略证书 SSLContext 失败", e);
        }
    }

    public record AgentHttpResponse(int statusCode, String contentType, Map<String, String> headers, byte[] bodyBytes, String body) {
        public boolean is2xx() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public static final class AgentHttpRequest {
        private final String method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, Object> form = new LinkedHashMap<>();
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        private boolean ignoreSsl;
        private boolean followRedirects = true;
        private String body;
        private String contentType;
        private Charset charset = CharsetUtil.CHARSET_UTF_8;

        private AgentHttpRequest(String method, String url) {
            this.method = method;
            this.url = url;
        }

        public static AgentHttpRequest get(String url) {
            return new AgentHttpRequest("GET", url);
        }

        public static AgentHttpRequest post(String url) {
            return new AgentHttpRequest("POST", url);
        }

        public AgentHttpRequest method(String method) {
            return new AgentHttpRequest(method == null ? "GET" : method.trim().toUpperCase(), url)
                    .headers(headers)
                    .form(form)
                    .timeoutMs(timeoutMs)
                    .ignoreSsl(ignoreSsl)
                    .followRedirects(followRedirects)
                    .body(body)
                    .contentType(contentType)
                    .charset(charset);
        }

        public AgentHttpRequest header(String key, String value) {
            if (key != null && !key.isBlank() && value != null) {
                headers.put(key, value);
            }
            return this;
        }

        public AgentHttpRequest headers(Map<String, String> headers) {
            if (headers != null) {
                headers.forEach(this::header);
            }
            return this;
        }

        public AgentHttpRequest form(Map<String, Object> form) {
            if (form != null) {
                this.form.putAll(form);
            }
            return this;
        }

        public AgentHttpRequest timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public AgentHttpRequest ignoreSsl(boolean ignoreSsl) {
            this.ignoreSsl = ignoreSsl;
            return this;
        }

        public AgentHttpRequest followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public AgentHttpRequest body(String body) {
            this.body = body;
            return this;
        }

        public AgentHttpRequest contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public AgentHttpRequest charset(Charset charset) {
            this.charset = charset == null ? CharsetUtil.CHARSET_UTF_8 : charset;
            return this;
        }

        public String method() { return method; }
        public String url() { return url; }
        public Map<String, String> headers() { return headers; }
        public Map<String, Object> form() { return form; }
        public int timeoutMs() { return timeoutMs; }
        public boolean ignoreSsl() { return ignoreSsl; }
        public boolean followRedirects() { return followRedirects; }
        public String body() { return body; }
        public String contentType() { return contentType; }
        public Charset charset() { return charset; }
    }
}
