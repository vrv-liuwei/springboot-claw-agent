package com.github.clawagent.server.dto;

public record LocalHealthItemView(
        String key,
        String label,
        String status,
        String summary,
        String detail
) {
}
