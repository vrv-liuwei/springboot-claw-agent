package com.github.clawagent.server.dto;

/**
 * 设备配对完成响应。
 * deviceSecret 只返回一次，客户端需要自行保存。
 */
public record DevicePairResponse(
        DeviceView device,
        String deviceSecret
) {
}
