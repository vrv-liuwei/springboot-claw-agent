package com.github.clawagent.server.dto;

/**
 * 设备绑定本地用户请求。
 * userId 为空时表示解绑；username 只作为展示冗余，不参与凭证校验。
 */
public record DeviceUserBindRequest(
        String userId,
        String username
) {
}
