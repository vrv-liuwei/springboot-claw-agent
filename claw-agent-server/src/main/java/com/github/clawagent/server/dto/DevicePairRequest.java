package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 使用配对码完成设备绑定。
 * metadata 可携带客户端版本、主机名等非敏感信息。
 */
public record DevicePairRequest(
        String code,
        Map<String, String> metadata
) {
}
