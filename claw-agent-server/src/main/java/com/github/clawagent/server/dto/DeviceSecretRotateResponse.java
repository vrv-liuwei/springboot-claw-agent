package com.github.clawagent.server.dto;

/**
 * 设备密钥轮换响应。
 * 新密钥只返回一次，服务端继续只保存哈希和前缀。
 */
public record DeviceSecretRotateResponse(
        DeviceView device,
        String deviceSecret
) {
}
