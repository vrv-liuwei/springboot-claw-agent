package com.github.clawagent.server.dto;

import java.time.Instant;

/**
 * 设备配对码响应。
 * code 只在创建时返回一次，落盘只保存哈希。
 */
public record DevicePairingCodeResponse(
        DeviceView device,
        String code,
        Instant expiresAt
) {
}
