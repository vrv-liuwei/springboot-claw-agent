package com.github.clawagent.server.controller;

import com.github.clawagent.core.MemoryHitLog;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.core.MemorySearchHit;
import com.github.clawagent.core.MemorySearchRequest;
import com.github.clawagent.core.MemoryUpsertRequest;
import com.github.clawagent.server.dto.MemorySearchPayload;
import com.github.clawagent.server.dto.MemorySearchResponse;
import com.github.clawagent.server.dto.VectorStatusView;
import com.github.clawagent.server.service.VectorStatusQueryService;
import com.github.clawagent.spi.MemoryProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 长期记忆管理接口，负责审核、检索和状态维护。
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {
    private final MemoryProvider memoryProvider;
    private final VectorStatusQueryService vectorStatusQueryService;

    public MemoryController(MemoryProvider memoryProvider,
                            VectorStatusQueryService vectorStatusQueryService) {
        this.memoryProvider = memoryProvider;
        this.vectorStatusQueryService = vectorStatusQueryService;
    }

    /**
     * 查询当前记忆 Provider 的标识和能力。
     */
    @GetMapping("/memory/provider")
    public Map<String, Object> memoryProvider() {
        return Map.of(
                "id", memoryProvider.id(),
                "capabilities", memoryProvider.capabilities());
    }

    /**
     * 按用户、范围和状态列出记忆条目。
     */
    @GetMapping("/memory/items")
    public List<MemoryItem> memoryItems(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        // 列表接口始终带 userId，避免管理台看到其他用户的记忆。
        return memoryProvider.list(userId, scopeType, status, Math.min(Math.max(limit, 1), 500));
    }

    /**
     * 查看单条记忆详情。
     */
    @GetMapping("/memory/items/{itemId}")
    public MemoryItem memoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.find(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在或无权访问：" + itemId));
    }

    /**
     * 创建一条待审核或可用记忆。
     */
    @PostMapping("/memory/items")
    public MemoryItem createMemoryItem(@RequestBody MemoryUpsertRequest request) {
        return memoryProvider.save(toMemoryItem(request, null));
    }

    /**
     * 更新指定记忆的正文、范围和质量信息。
     */
    @PutMapping("/memory/items/{itemId}")
    public MemoryItem updateMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestBody MemoryUpsertRequest request) {
        return memoryProvider.save(toMemoryItem(request, itemId));
    }

    /**
     * 将记忆标记为可进入模型上下文。
     */
    @PostMapping("/memory/items/{itemId}/enable")
    public MemoryItem enableMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "active");
    }

    /**
     * 禁用记忆，保留记录但不进入检索上下文。
     */
    @PostMapping("/memory/items/{itemId}/disable")
    public MemoryItem disableMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "disabled");
    }

    /**
     * 归档记忆，通常用于拒绝候选或清理过期内容。
     */
    @PostMapping("/memory/items/{itemId}/archive")
    public MemoryItem archiveMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "archived");
    }

    /**
     * 删除指定记忆条目。
     */
    @DeleteMapping("/memory/items/{itemId}")
    public Map<String, Object> deleteMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        memoryProvider.delete(userId, itemId);
        return Map.of("deleted", true, "itemId", itemId);
    }

    /**
     * 查询待审核和冲突状态的记忆候选。
     */
    @GetMapping("/memory/candidates")
    public List<MemoryItem> memoryCandidates(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        // pending/conflict 都只在管理台审核，不进入模型上下文。
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        List<MemoryItem> pending = memoryProvider.list(userId, null, "pending", safeLimit);
        List<MemoryItem> conflicts = memoryProvider.list(userId, null, "conflict", safeLimit);
        return java.util.stream.Stream.concat(pending.stream(), conflicts.stream())
                .sorted(java.util.Comparator.comparing(MemoryItem::updatedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 接受候选记忆，使其进入 active 状态。
     */
    @PostMapping("/memory/candidates/{itemId}/accept")
    public MemoryItem acceptMemoryCandidate(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "active");
    }

    /**
     * 拒绝候选记忆，将其归档。
     */
    @PostMapping("/memory/candidates/{itemId}/reject")
    public MemoryItem rejectMemoryCandidate(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "archived");
    }

    /**
     * 按关键词、向量或混合模式检索长期记忆。
     */
    @PostMapping("/memory/search")
    public MemorySearchResponse searchMemory(@RequestBody MemorySearchPayload payload) {
        MemorySearchPayload safePayload = payload == null
                ? new MemorySearchPayload(null, null, List.of(), null, List.of(), null, null)
                : payload;
        List<String> scopes = safePayload.scopeTypes() == null || safePayload.scopeTypes().isEmpty()
                ? List.of("global", "channel", "session")
                : safePayload.scopeTypes();
        List<String> statuses = safePayload.statuses() == null || safePayload.statuses().isEmpty()
                ? List.of("active")
                : safePayload.statuses();
        // 检索接口只传轻量条件，具体 BM25/JVector/RRF 融合由 provider 负责。
        List<MemorySearchHit> hits = memoryProvider.search(new MemorySearchRequest(
                firstNonBlank(safePayload.userId(), "console"),
                safePayload.query(),
                scopes,
                safePayload.scopeId(),
                statuses,
                firstNonBlank(safePayload.mode(), "hybrid"),
                safePayload.topK() == null ? 8 : safePayload.topK()));
        return new MemorySearchResponse(hits);
    }

    /**
     * 查询任务或会话命中过哪些记忆。
     */
    @GetMapping("/memory/hits")
    public List<MemoryHitLog> memoryHits(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return memoryProvider.hits(userId, sessionId, taskId, Math.min(Math.max(limit, 1), 500));
    }

    /**
     * 查询长期记忆的向量化进度。
     */
    @GetMapping("/memory/vector-status")
    public List<VectorStatusView> memoryVectorStatus(@RequestParam(name = "userId", defaultValue = "console") String userId) {
        return vectorStatusQueryService.memoryVectorStatus(userId);
    }

    private MemoryItem toMemoryItem(MemoryUpsertRequest request, String forcedId) {
        if (request == null) {
            throw new IllegalArgumentException("记忆请求不能为空");
        }
        String content = firstNonBlank(request.content(), "");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("记忆正文不能为空");
        }
        String scopeType = firstNonBlank(request.scopeType(), "session").toLowerCase();
        if ("task".equals(scopeType) || "workspace".equals(scopeType)) {
            // task 只是本轮运行上下文，workspace 是预留能力，不能被写成长期记忆。
            throw new IllegalArgumentException("当前版本不支持长期记忆 scope：" + scopeType);
        }
        if (!List.of("global", "channel", "session").contains(scopeType)) {
            throw new IllegalArgumentException("不支持的记忆 scope：" + scopeType);
        }
        Instant now = Instant.now();
        return new MemoryItem(
                firstNonBlank(forcedId, firstNonBlank(request.id(), UUID.randomUUID().toString())),
                firstNonBlank(request.userId(), "console"),
                scopeType,
                firstNonBlank(request.scopeId(), ""),
                firstNonBlank(request.type(), "fact"),
                firstNonBlank(request.status(), "pending"),
                content,
                firstNonBlank(request.summary(), preview(content, 120)),
                firstNonBlank(request.sourceSessionId(), ""),
                firstNonBlank(request.sourceTaskId(), ""),
                request.importance() == null ? 0.5 : request.importance(),
                request.confidence() == null ? 0.7 : request.confidence(),
                request.metadata() == null ? Map.of() : request.metadata(),
                now,
                now);
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
