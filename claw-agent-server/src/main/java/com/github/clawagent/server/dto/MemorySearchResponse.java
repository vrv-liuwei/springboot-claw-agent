package com.github.clawagent.server.dto;

import com.github.clawagent.core.MemorySearchHit;

import java.util.List;

/**
 * 记忆检索 HTTP 响应。
 *
 * @param hits 记忆检索命中的片段列表。
 */
public record MemorySearchResponse(
        List<MemorySearchHit> hits
) {
}
