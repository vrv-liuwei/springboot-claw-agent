package com.github.clawagent.server.dto;

import java.util.List;

public record LocalHealthView(
        String status,
        List<LocalHealthItemView> items
) {
}
