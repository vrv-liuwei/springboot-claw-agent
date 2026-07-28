package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 策略解析预览请求。
 * 管理台可传入任务即将携带的 metadata，提前查看用户、Token、设备和 Agent 隔离会如何收敛。
 */
public record PolicyResolveRequest(
        String channelId,
        String userId,
        Map<String, String> metadata
) {
}
