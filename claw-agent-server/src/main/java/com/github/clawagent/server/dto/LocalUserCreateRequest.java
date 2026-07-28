package com.github.clawagent.server.dto;

import java.util.Map;

public record LocalUserCreateRequest(
        String username,
        String password,
        String displayName,
        String role,
        Map<String, String> metadata
) {
}
