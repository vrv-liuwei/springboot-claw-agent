package com.github.clawagent.server.dto;

/**
 * 设备密钥校验请求。
 * 用于本地客户端启动时确认自己仍是已配对设备。
 */
public record DeviceSecretVerifyRequest(
        String deviceSecret
) {
}
