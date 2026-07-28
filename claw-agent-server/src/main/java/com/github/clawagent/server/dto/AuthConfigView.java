package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

public record AuthConfigView(
        boolean required,
        boolean apiTokenRequired,
        List<String> protectedPathPatterns,
        List<String> excludedPathPatterns,
        boolean initialized,
        long userCount,
        boolean ownerExists,
        List<String> supportedRoles,
        Map<String, AuthRolePolicyView> rolePolicies
) {
}
