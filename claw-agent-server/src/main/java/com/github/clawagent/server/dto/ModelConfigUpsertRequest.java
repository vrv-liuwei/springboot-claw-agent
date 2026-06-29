package com.github.clawagent.server.dto;

/**
 * 新增或更新模型池中的一个模型。
 *
 * @param id 模型配置 ID，用于 default-model 或 memory-model 引用。
 * @param provider 模型供应商标识，例如 deepseek、siliconflow。
 * @param baseUrl OpenAI-compatible API 根地址。
 * @param model 供应商真实模型名。
 * @param apiKey API Key 明文；只写入本地覆盖配置，不写日志。
 * @param temperature 采样温度。
 * @param timeoutSeconds 请求超时秒数。
 * @param vision 是否支持图片输入。
 */
public record ModelConfigUpsertRequest(
        String id,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        Double temperature,
        Integer timeoutSeconds,
        Boolean vision
) {
}
