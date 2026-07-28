package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 通道外部用户绑定请求。
 * 只保存外部用户标识到本地用户的映射，不保存飞书、钉钉、DDIO 的访问凭证。
 */
public record ChannelUserBindingRequest(
        String externalUserId,
        String externalUsername,
        String localUserId,
        String localUsername,
        Map<String, String> metadata
) {
}
