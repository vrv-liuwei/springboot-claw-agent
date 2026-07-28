package com.github.clawagent.server.dto;

public record LocalUserLoginRequest(
        String username,
        String password
) {
}
