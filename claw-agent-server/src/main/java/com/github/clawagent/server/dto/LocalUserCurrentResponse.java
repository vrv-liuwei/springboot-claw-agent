package com.github.clawagent.server.dto;

public record LocalUserCurrentResponse(
        LocalUserView user,
        LocalUserSessionView session
) {
}
