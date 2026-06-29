package com.github.clawagent.server.dto;

import java.util.List;

public record AuthConfigView(
        boolean apiTokenRequired,
        List<String> protectedPathPatterns,
        List<String> excludedPathPatterns
) {
}
