package com.github.clawagent.server.dto;

/**
 * 设备密钥校验结果。
 */
public record DeviceSecretVerifyResponse(
        String deviceId,
        boolean verified,
        String status
) {
}
