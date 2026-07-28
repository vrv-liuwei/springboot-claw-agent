package com.github.clawagent.server.dto;

import java.util.List;

public record AuthSetupView(
        boolean initialized,
        long userCount,
        boolean ownerExists,
        List<String> supportedRoles
) {
}
