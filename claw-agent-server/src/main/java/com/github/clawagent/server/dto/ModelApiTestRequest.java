package com.github.clawagent.server.dto;

/**
 * 模型在线测试请求。
 *
 * @param provider 模型供应商标识，仅用于页面展示，测试请求按 baseUrl 调用。
 * @param baseUrl OpenAI-compatible API 根地址。
 * @param model 供应商真实模型名。
 * @param apiKey API Key 明文；只用于本次测试，不持久化。
 * @param prompt 测试提示词。
 * @param temperature 采样温度。
 * @param timeoutSeconds 请求超时秒数。
 */
public record ModelApiTestRequest(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        String prompt,
        Double temperature,
        Integer timeoutSeconds
) {
}
