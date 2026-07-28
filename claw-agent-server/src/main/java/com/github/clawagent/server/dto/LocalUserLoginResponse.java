package com.github.clawagent.server.dto;

public record LocalUserLoginResponse(
        LocalUserView user,
        LocalUserSessionView session,
        String sessionToken
) {
}
