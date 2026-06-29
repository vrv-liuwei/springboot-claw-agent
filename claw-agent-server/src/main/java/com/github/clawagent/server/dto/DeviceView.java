package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 设备管理视图。
 * 用于展示哪些本地客户端、桌面壳或外部接入端曾连接过主服务。
 */
public record DeviceView(
        String id,
        String name,
        String type,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant revokedAt,
        Map<String, String> metadata
) {
}
