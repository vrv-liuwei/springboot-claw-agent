package com.github.clawagent.toolkit.webfetch;

import java.util.Map;

/**
 * WebFetchResponse 是内置网页请求的标准响应对象。
 */
public record WebFetchResponse(
        /** 最终请求 URL。 */
        String url,
        /** HTTP 状态码。 */
        int status,
        /** 响应 Content-Type。 */
        String contentType,
        /** UTF-8 解码后的响应正文。 */
        String body,
        /** 响应头，主要用于调试和审计。 */
        Map<String, String> headers) {
}
