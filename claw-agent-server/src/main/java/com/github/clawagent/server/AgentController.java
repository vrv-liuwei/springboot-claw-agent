package com.github.clawagent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;
import com.github.clawagent.core.AutomationScheduleType;
import com.github.clawagent.core.AutomationStatus;
import com.github.clawagent.core.AttachmentParseResult;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.MemoryHitLog;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.core.MemorySearchHit;
import com.github.clawagent.core.MemorySearchRequest;
import com.github.clawagent.core.MemoryUpsertRequest;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.knowledge.AttachmentService;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.mcp.McpImportRequest;
import com.github.clawagent.mcp.McpServerConfig;
import com.github.clawagent.mcp.McpServerRegistration;
import com.github.clawagent.mcp.McpPromptDescriptor;
import com.github.clawagent.mcp.McpPromptContent;
import com.github.clawagent.mcp.McpPromptGetRequest;
import com.github.clawagent.mcp.McpResourceDescriptor;
import com.github.clawagent.mcp.McpResourceContent;
import com.github.clawagent.mcp.McpToolDescriptor;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.skill.SkillPackage;
import com.github.clawagent.skill.SkillRegistration;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.AutomationStore;
import com.github.clawagent.spi.FileStorageProvider;
import com.github.clawagent.spi.MemoryProvider;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.spring.automation.AutomationSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M1 管理 API。
 * 后续 Gateway/Auth/Channel 会包裹这些接口；当前先保证本地平台可直接验证运行链路。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRuntime runtime;
    private final AgentToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final TodoStore todoStore;
    private final AutomationStore automationStore;
    private final AutomationSchedulerService automationSchedulerService;
    private final AttachmentService attachmentService;
    private final FileStorageProvider fileStorageProvider;
    private final KnowledgeService knowledgeService;
    private final MemoryProvider memoryProvider;
    private final SystemLogQueryService systemLogQueryService;
    private final ClawAgentProperties properties;
    /** Controller 内部 JSON 工具，只用于模型在线测试和轻量状态接口。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 在线测试模型 API 的短连接客户端，不参与正式 Runtime 调用链路。 */
    private final HttpClient configTestHttpClient = HttpClient.newHttpClient();
    /** 流式任务后台执行池；避免阻塞 Spring MVC 请求线程。 */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AgentController(AgentRuntime runtime,
                           AgentToolRegistry toolRegistry,
                           McpRegistry mcpRegistry,
                           SkillRegistry skillRegistry,
                           TodoStore todoStore,
                           @Qualifier("automationStore") AutomationStore automationStore,
                           AutomationSchedulerService automationSchedulerService,
                           AttachmentService attachmentService,
                           FileStorageProvider fileStorageProvider,
                           KnowledgeService knowledgeService,
                           MemoryProvider memoryProvider,
                           SystemLogQueryService systemLogQueryService,
                           ClawAgentProperties properties) {
        this.runtime = runtime;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.todoStore = todoStore;
        this.automationStore = automationStore;
        this.automationSchedulerService = automationSchedulerService;
        this.attachmentService = attachmentService;
        this.fileStorageProvider = fileStorageProvider;
        this.knowledgeService = knowledgeService;
        this.memoryProvider = memoryProvider;
        this.systemLogQueryService = systemLogQueryService;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "name", "clawagent");
    }

    @GetMapping("/logs/query")
    public List<SystemLogQueryService.SystemLogLine> queryLogs(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "logger", required = false) String loggerName,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", required = false) Integer limit) throws IOException {
        // 系统日志按需查询本地 log/gz 文件，不进入 AgentEventStore，避免和会话执行事件混在一起。
        return systemLogQueryService.query(new SystemLogQueryService.SystemLogQuery(
                from,
                to,
                level,
                keyword,
                loggerName,
                userId,
                sessionId,
                taskId,
                limit));
    }

    @GetMapping("/logs/sources")
    public List<SystemLogQueryService.SystemLogSource> logSources() throws IOException {
        return systemLogQueryService.sources();
    }

    @PostMapping(value = "/attachments/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentParseResponse parseAttachments(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(name = "userId", defaultValue = "console") String userId) throws IOException {
        int count = files == null ? 0 : files.size();
        log.info("attachment parse requested count={} userId={}", count, userId);
        List<AttachmentService.UploadFile> uploadFiles = new ArrayList<>();
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            uploadFiles.add(new AttachmentService.UploadFile(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        // Controller 只负责 HTTP 协议适配；存储介质和解析策略留在 knowledge 模块，方便后续切换 MinIO 或 RAGFlow。
        return new AttachmentParseResponse(attachmentService.parse(uploadFiles, userId));
    }

    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable("attachmentId") String attachmentId) throws IOException {
        return attachmentResponse(attachmentId, true);
    }

    @GetMapping("/attachments/{attachmentId}/view")
    public ResponseEntity<Resource> viewAttachment(@PathVariable("attachmentId") String attachmentId) throws IOException {
        return attachmentResponse(attachmentId, false);
    }

    private ResponseEntity<Resource> attachmentResponse(String attachmentId, boolean download) throws IOException {
        StoredFile metadata = fileStorageProvider.read(attachmentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(metadata.originalName(), StandardCharsets.UTF_8)
                .build();
        // 完整文件只通过附件读取接口返回，避免在聊天消息或 SSE 中塞入原始内容。
        return ResponseEntity.ok()
                .contentType(attachmentMediaType(metadata.contentType()))
                .contentLength(metadata.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(metadata.localPath()));
    }

    private MediaType attachmentMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @GetMapping("/knowledge/providers")
    public List<Map<String, Object>> knowledgeProviders() {
        return knowledgeService.providers();
    }

    @PostMapping(value = "/knowledge/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<KnowledgeDocument> uploadKnowledgeDocuments(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(name = "userId", defaultValue = "console") String userId) throws IOException {
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            String kind = detectKnowledgeKind(file.getContentType(), file.getOriginalFilename());
            // userId 在 KnowledgeService/provider 内继续校验隔离，Controller 只透传当前请求用户。
            documents.add(knowledgeService.ingest(
                    userId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    kind,
                    file.getBytes(),
                    Map.of("source", "admin-upload")));
        }
        return documents;
    }

    @GetMapping("/knowledge/documents")
    public List<KnowledgeDocument> knowledgeDocuments(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return knowledgeService.list(userId, limit);
    }

    @GetMapping("/knowledge/documents/{documentId}/download")
    public ResponseEntity<Resource> downloadKnowledgeDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        StoredFile file = knowledgeService.download(userId, documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(attachmentMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.localPath()));
    }

    @DeleteMapping("/knowledge/documents/{documentId}")
    public Map<String, Object> deleteKnowledgeDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        knowledgeService.delete(userId, documentId);
        return Map.of("deleted", true, "documentId", documentId);
    }

    @PostMapping("/knowledge/search")
    public KnowledgeSearchResponse searchKnowledge(@RequestBody KnowledgeSearchPayload payload) {
        KnowledgeSearchPayload safePayload = payload == null ? new KnowledgeSearchPayload(null, null, List.of(), null, null) : payload;
        List<KnowledgeSearchResult> hits = knowledgeService.search(
                firstNonBlank(safePayload.userId(), "console"),
                safePayload.query(),
                safePayload.documentIds() == null ? List.of() : safePayload.documentIds(),
                firstNonBlank(safePayload.mode(), "hybrid"),
                safePayload.topK() == null ? 8 : safePayload.topK());
        return new KnowledgeSearchResponse(hits);
    }

    @GetMapping("/memory/provider")
    public Map<String, Object> memoryProvider() {
        return Map.of(
                "id", memoryProvider.id(),
                "capabilities", memoryProvider.capabilities());
    }

    @GetMapping("/memory/items")
    public List<MemoryItem> memoryItems(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "scopeType", required = false) String scopeType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        // 列表接口始终带 userId，避免管理台看到其他用户的记忆。
        return memoryProvider.list(userId, scopeType, status, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/memory/items/{itemId}")
    public MemoryItem memoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.find(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在或无权访问：" + itemId));
    }

    @PostMapping("/memory/items")
    public MemoryItem createMemoryItem(@RequestBody MemoryUpsertRequest request) {
        return memoryProvider.save(toMemoryItem(request, null));
    }

    @PutMapping("/memory/items/{itemId}")
    public MemoryItem updateMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestBody MemoryUpsertRequest request) {
        return memoryProvider.save(toMemoryItem(request, itemId));
    }

    @PostMapping("/memory/items/{itemId}/enable")
    public MemoryItem enableMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "active");
    }

    @PostMapping("/memory/items/{itemId}/disable")
    public MemoryItem disableMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "disabled");
    }

    @PostMapping("/memory/items/{itemId}/archive")
    public MemoryItem archiveMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "archived");
    }

    @DeleteMapping("/memory/items/{itemId}")
    public Map<String, Object> deleteMemoryItem(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        memoryProvider.delete(userId, itemId);
        return Map.of("deleted", true, "itemId", itemId);
    }

    @GetMapping("/memory/candidates")
    public List<MemoryItem> memoryCandidates(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        // pending 候选只在管理台审核，不进入模型上下文。
        return memoryProvider.list(userId, null, "pending", Math.min(Math.max(limit, 1), 500));
    }

    @PostMapping("/memory/candidates/{itemId}/accept")
    public MemoryItem acceptMemoryCandidate(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "active");
    }

    @PostMapping("/memory/candidates/{itemId}/reject")
    public MemoryItem rejectMemoryCandidate(
            @PathVariable("itemId") String itemId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        return memoryProvider.updateStatus(userId, itemId, "archived");
    }

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

    @GetMapping("/memory/hits")
    public List<MemoryHitLog> memoryHits(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return memoryProvider.hits(userId, sessionId, taskId, Math.min(Math.max(limit, 1), 500));
    }

    @PostMapping("/tasks")
    public AgentResult submit(@RequestBody AgentRequest request) {
        log.info("agent task submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        AgentResult result = runtime.submit(knowledgeService.enrichForModel(request));
        log.info("agent task submit finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    @PostMapping("/tasks/stream")
    public SseEmitter submitStream(@RequestBody AgentRequest request) {
        log.info("agent task stream submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                AgentResult result = runtime.submitStream(knowledgeService.enrichForModel(request), new AgentCallback() {
                            @Override
                            public void onEvent(String eventType, String taskId, String message) {
                                onEvent(eventType, taskId, message, Map.of());
                            }

                            @Override
                            public void onEvent(String eventType, String taskId, String message, Map<String, String> details) {
                                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("eventType", eventType);
                                payload.put("taskId", nullToEmpty(taskId));
                                payload.put("message", nullToEmpty(message));
                                if (details != null) {
                                    payload.putAll(details);
                                }
                                sendSse(emitter, eventType, payload);
                            }
                        },
                        new com.github.clawagent.spi.ChatStreamCallback() {
                            @Override
                            public void onDelta(String content) {
                                String delta = normalizeStreamContent(content);
                                if (!delta.isBlank()) {
                                    // 模型流式协议里可能出现 null 片段，后端先过滤，避免前端聊天窗口出现 nullnull。
                                    sendSse(emitter, "llm.delta", Map.of("content", delta));
                                }
                            }

                            @Override
                            public void onComplete(String content) {
                                sendSse(emitter, "llm.completed", Map.of("length", String.valueOf(nullToEmpty(content).length())));
                            }
                        });
                sendSse(emitter, "result", Map.of(
                        "taskId", result.taskId(),
                        "status", result.status().name(),
                        "answer", result.answer()));
                emitter.complete();
            } catch (RuntimeException e) {
                sendSse(emitter, "error", Map.of("message", nullToEmpty(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/assistant")
    public AgentResult assistant(@RequestParam("query") String query) {
        log.info("assistant query received input={}", preview(query));
        AgentResult result = runtime.submit(AgentRequest.userMessage(query));
        log.info("assistant query finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    @PostMapping("/sessions")
    public AgentSession createSession(@RequestBody SessionCreateRequest request) {
        log.info("session create received channelId={} userId={} title={}", request.channelId(), request.userId(), preview(request.title()));
        return runtime.createSession(request);
    }

    @PostMapping("/sessions/id")
    public Map<String, String> createSessionId() {
        String sessionId = runtime.createSessionId();
        log.info("session id allocated sessionId={}", sessionId);
        return Map.of("sessionId", sessionId);
    }

    @DeleteMapping("/sessions")
    public Map<String, Object> clearSessions() {
        log.warn("session clear all requested");
        Map<String, Object> result = runtime.clearAllSessions();
        if (todoStore instanceof AgentDataCleaner cleaner) {
            // TodoStore 可能是独立内存实现；SQLite 场景下重复 delete 空表也是安全的。
            cleaner.clearAllAgentData();
        }
        return result;
    }

    @GetMapping("/sessions")
    public List<AgentSession> sessions(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        log.debug("session list requested limit={}", limit);
        return runtime.listSessions(limit);
    }

    @GetMapping("/sessions/{sessionId}")
    public AgentSession session(@PathVariable("sessionId") String sessionId) {
        return runtime.getSession(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/tasks")
    public List<AgentTask> sessionTasks(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return runtime.getSessionTasks(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<AgentMessage> sessionMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return runtime.getSessionMessages(sessionId, limit);
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public AgentSession summarizeSession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        log.info("session summary requested sessionId={} limit={}", sessionId, limit);
        return runtime.summarizeSession(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> sessionEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        log.debug("session events requested sessionId={} limit={}", sessionId, limit);
        return runtime.getSessionEvents(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/token-usage")
    public TokenUsageSummary sessionTokenUsage(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        log.debug("session token usage requested sessionId={} limit={}", sessionId, limit);
        return runtime.getSessionTokenUsage(sessionId, limit);
    }

    @PostMapping("/mcp/servers")
    public McpServerRegistration registerMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server register received id={} name={} transport={}", config.id(), config.name(), config.transport());
        return mcpRegistry.register(config);
    }

    @PostMapping("/mcp/servers/test")
    public McpServerRegistration testMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server test received id={} name={} transport={}", config.id(), config.name(), config.transport());
        return mcpRegistry.test(config);
    }

    @PostMapping("/mcp/import")
    public List<McpServerRegistration> importMcpServers(@RequestBody McpImportRequest request) {
        log.info("mcp import requested");
        return mcpRegistry.importServers(request.json());
    }

    @GetMapping("/mcp/servers")
    public List<McpServerRegistration> mcpServers() {
        return mcpRegistry.list();
    }

    @GetMapping("/mcp/servers/{serverId}")
    public McpServerRegistration mcpServer(@PathVariable("serverId") String serverId) {
        return mcpRegistry.find(serverId).orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在：" + serverId));
    }

    @PostMapping("/mcp/servers/{serverId}/connect")
    public McpServerRegistration connectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server connect requested id={}", serverId);
        return mcpRegistry.connect(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/disconnect")
    public McpServerRegistration disconnectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server disconnect requested id={}", serverId);
        return mcpRegistry.disconnect(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-tools")
    public List<McpToolDescriptor> refreshMcpTools(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh tools requested id={}", serverId);
        return mcpRegistry.refreshTools(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/tools")
    public List<McpToolDescriptor> mcpTools(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listTools(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-resources")
    public List<McpResourceDescriptor> refreshMcpResources(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh resources requested id={}", serverId);
        return mcpRegistry.refreshResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources")
    public List<McpResourceDescriptor> mcpResources(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources/read")
    public McpResourceContent readMcpResource(
            @PathVariable("serverId") String serverId,
            @RequestParam("uri") String uri) {
        log.info("mcp resource read requested id={} uri={}", serverId, uri);
        return mcpRegistry.readResource(serverId, uri);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-prompts")
    public List<McpPromptDescriptor> refreshMcpPrompts(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh prompts requested id={}", serverId);
        return mcpRegistry.refreshPrompts(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/prompts")
    public List<McpPromptDescriptor> mcpPrompts(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listPrompts(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/prompts/{promptName}/get")
    public McpPromptContent getMcpPrompt(
            @PathVariable("serverId") String serverId,
            @PathVariable("promptName") String promptName,
            @RequestBody(required = false) McpPromptGetRequest request) {
        log.info("mcp prompt get requested id={} name={}", serverId, promptName);
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return mcpRegistry.getPrompt(serverId, promptName, arguments);
    }

    @PostMapping("/skills")
    public SkillRegistration installSkill(@RequestBody SkillPackage skillPackage) {
        log.info("skill install received id={} name={}",
                skillPackage.manifest() == null ? null : skillPackage.manifest().id(),
                skillPackage.manifest() == null ? null : skillPackage.manifest().name());
        return skillRegistry.install(skillPackage);
    }

    @GetMapping("/skills")
    public List<SkillRegistration> skills() {
        return skillRegistry.list();
    }

    @GetMapping("/skills/{skillId}")
    public SkillRegistration skill(@PathVariable("skillId") String skillId) {
        return skillRegistry.find(skillId).orElseThrow(() -> new IllegalArgumentException("Skill 不存在：" + skillId));
    }

    @PostMapping("/skills/{skillId}/enable")
    public SkillRegistration enableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill enable requested id={}", skillId);
        return skillRegistry.enable(skillId);
    }

    @PostMapping("/skills/{skillId}/disable")
    public SkillRegistration disableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill disable requested id={}", skillId);
        return skillRegistry.disable(skillId);
    }

    @GetMapping("/config/runtime")
    public RuntimeConfigSnapshot runtimeConfig() {
        return buildRuntimeConfigSnapshot("当前运行配置快照。", false, true);
    }

    @PutMapping("/config/model")
    public RuntimeConfigSnapshot saveModelConfig(@RequestBody ModelConfigUpdate update) {
        applyModelConfigUpdate(update);
        writeRuntimeConfigFile();
        return buildRuntimeConfigSnapshot("模型配置已保存到本地 YAML，重启服务后生效。", true, false);
    }

    @PostMapping("/config/models")
    public RuntimeConfigSnapshot upsertModelConfig(@RequestBody ModelConfigUpsertRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        ClawAgentProperties.ModelConfig config = ensureModelConfig(request.id().trim());
        // 这里仅修改模型池，不改变当前聊天模型；用户可在模型配置里显式选择默认模型。
        config.setProvider(firstNonBlank(request.provider(), config.getProvider()));
        config.setBaseUrl(firstNonBlank(request.baseUrl(), config.getBaseUrl()));
        config.setModel(firstNonBlank(request.model(), firstNonBlank(config.getModel(), request.id())));
        config.setApiKey(firstNonBlank(request.apiKey(), config.getApiKey()));
        if (request.temperature() != null) {
            config.setTemperature(request.temperature());
        }
        if (request.timeoutSeconds() != null) {
            config.setTimeoutSeconds(request.timeoutSeconds());
        }
        writeRuntimeConfigFile();
        return buildRuntimeConfigSnapshot("模型已加入本地模型池，重启服务后可用于正式调用。", true, false);
    }

    @PostMapping("/config/model/test")
    public ModelApiTestResponse testModelApi(@RequestBody ModelApiTestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("测试请求不能为空");
        }
        long started = System.nanoTime();
        try {
            String baseUrl = firstNonBlank(request.baseUrl(), "");
            String model = firstNonBlank(request.model(), "");
            String apiKey = resolveInlineApiKey(firstNonBlank(request.apiKey(), ""));
            if (baseUrl.isBlank() || model.isBlank()) {
                throw new IllegalArgumentException("Base URL 和模型名称不能为空");
            }
            if (apiKey.isBlank()) {
                throw new IllegalArgumentException("API Key 不能为空，或环境变量占位符未解析到值");
            }
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", firstNonBlank(request.prompt(), "请回复：模型连接正常。"))),
                    "temperature", request.temperature() == null ? 0.2 : request.temperature()
            );
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(request.timeoutSeconds() == null ? 30 : Math.max(1, request.timeoutSeconds())))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = configTestHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ModelApiTestResponse(false, response.statusCode(), "模型 API 返回错误，HTTP " + response.statusCode(), preview(response.body(), 800), 0, 0, 0, elapsedMs);
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            return new ModelApiTestResponse(
                    true,
                    response.statusCode(),
                    firstNonBlank(content, "模型连接正常，但响应没有 message.content。"),
                    "",
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0),
                    elapsedMs
            );
        } catch (Exception ex) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new ModelApiTestResponse(false, 0, "模型测试失败：" + ex.getMessage(), "", 0, 0, 0, elapsedMs);
        }
    }

    @GetMapping("/knowledge/vector-status")
    public List<VectorStatusView> knowledgeVectorStatus(@RequestParam(name = "userId", defaultValue = "console") String userId) {
        return queryVectorStatus(
                "knowledge_document",
                "knowledge_chunk",
                "knowledge_vector",
                "document_id",
                "id",
                "name",
                "status",
                userId);
    }

    @GetMapping("/memory/vector-status")
    public List<VectorStatusView> memoryVectorStatus(@RequestParam(name = "userId", defaultValue = "console") String userId) {
        return queryVectorStatus(
                "memory_item",
                "memory_chunk",
                "memory_vector",
                "item_id",
                "id",
                "summary",
                "status",
                userId);
    }

    @GetMapping("/tasks/{taskId}")
    public AgentTask task(@PathVariable("taskId") String taskId) {
        return runtime.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public AgentTask cancelTask(@PathVariable("taskId") String taskId) {
        log.warn("task cancel requested taskId={}", taskId);
        return runtime.cancelTask(taskId);
    }

    @GetMapping("/tasks/{taskId}/steps")
    public List<AgentStep> steps(@PathVariable("taskId") String taskId) {
        return runtime.getSteps(taskId);
    }

    @GetMapping("/tasks/{taskId}/messages")
    public List<AgentMessage> taskMessages(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.debug("task messages requested taskId={} limit={}", taskId, limit);
        return runtime.getTaskMessages(taskId, limit);
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<AgentEvent> taskEvents(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "todoId", required = false) String todoId,
            @RequestParam(name = "stepId", required = false) String stepId) {
        log.debug("task events requested taskId={} limit={} todoId={} stepId={}", taskId, limit, todoId, stepId);
        List<AgentEvent> events = runtime.getTaskEvents(taskId, limit);
        if (todoId != null && !todoId.isBlank()) {
            events = events.stream()
                    .filter(event -> todoId.equals(event.details().get("todoId")))
                    .toList();
        }
        if (stepId != null && !stepId.isBlank()) {
            events = events.stream()
                    .filter(event -> stepId.equals(event.details().get("stepId")))
                    .toList();
        }
        return events;
    }

    @GetMapping("/tasks/{taskId}/token-usage")
    public TokenUsageSummary taskTokenUsage(@PathVariable("taskId") String taskId) {
        log.debug("task token usage requested taskId={}", taskId);
        return runtime.getTaskTokenUsage(taskId);
    }

    @GetMapping("/tools")
    public Object tools() {
        log.debug("tool definitions requested");
        return toolRegistry.definitions();
    }

    @GetMapping("/todos")
    public List<TodoItem> todos(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.trace("todo list requested sessionId={} taskId={} limit={}", sessionId, taskId, limit);
        return todoStore.listTodoItems(sessionId, taskId, limit);
    }

    @GetMapping("/automations")
    public List<AutomationDefinition> automations(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return automationStore.listAutomations(limit);
    }

    @GetMapping("/automations/{automationId}")
    public AutomationDefinition automation(@PathVariable("automationId") String automationId) {
        return automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
    }

    @PostMapping("/automations")
    public AutomationDefinition createAutomation(@RequestBody AutomationUpsertRequest request) {
        AutomationDefinition automation = buildAutomation(UUID.randomUUID().toString(), request, null);
        log.info("automation create requested id={} name={} scheduleType={}",
                automation.id(), automation.name(), automation.scheduleType());
        automationStore.saveAutomation(automation);
        return automation;
    }

    @PutMapping("/automations/{automationId}")
    public AutomationDefinition updateAutomation(
            @PathVariable("automationId") String automationId,
            @RequestBody AutomationUpsertRequest request) {
        AutomationDefinition existing = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        AutomationDefinition automation = buildAutomation(automationId, request, existing);
        log.info("automation update requested id={} name={} scheduleType={} status={}",
                automation.id(), automation.name(), automation.scheduleType(), automation.status());
        automationStore.saveAutomation(automation);
        return automation;
    }

    @DeleteMapping("/automations/{automationId}")
    public Map<String, Object> deleteAutomation(@PathVariable("automationId") String automationId) {
        log.warn("automation delete requested id={}", automationId);
        automationStore.deleteAutomation(automationId);
        return Map.of("deleted", true, "automationId", automationId);
    }

    @PostMapping("/automations/{automationId}/enable")
    public AutomationDefinition enableAutomation(@PathVariable("automationId") String automationId) {
        AutomationDefinition automation = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        AutomationDefinition enabled = copyAutomationWithStatus(automation, AutomationStatus.ENABLED, Instant.now());
        log.info("automation enable requested id={}", automationId);
        AutomationDefinition refreshed = automationSchedulerService.refreshNextRun(enabled, Instant.now());
        automationStore.saveAutomation(refreshed);
        return refreshed;
    }

    @PostMapping("/automations/{automationId}/pause")
    public AutomationDefinition pauseAutomation(@PathVariable("automationId") String automationId) {
        AutomationDefinition automation = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        log.info("automation pause requested id={}", automationId);
        AutomationDefinition paused = copyAutomationWithNextRun(automation, AutomationStatus.PAUSED, null, Instant.now());
        automationStore.saveAutomation(paused);
        return paused;
    }

    @PostMapping("/automations/{automationId}/run")
    public AutomationDefinition runAutomationNow(@PathVariable("automationId") String automationId) {
        log.info("automation run now requested id={}", automationId);
        automationSchedulerService.runNow(automationId);
        return automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
    }

    @GetMapping("/automations/{automationId}/runs")
    public List<AutomationRun> automationRuns(
            @PathVariable("automationId") String automationId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return automationStore.listAutomationRuns(automationId, limit);
    }

    @PostMapping("/todos/{todoId}/status")
    public TodoItem updateTodoStatus(
            @PathVariable("todoId") String todoId,
            @RequestBody Map<String, String> body) {
        String status = body == null ? "" : body.getOrDefault("status", "");
        log.info("todo status update requested id={} status={}", todoId, status);
        return todoStore.updateTodoStatus(todoId, status);
    }

    public record RuntimeConfigSnapshot(
            String cwd,
            String configRoot,
            String configPath,
            boolean restartRequired,
            boolean applied,
            String message,
            ModelSettings model,
            ModelConfigView effectiveModel,
            EmbeddingConfigView embedding,
            MemoryExtractionConfigView memoryExtraction,
            Map<String, ModelConfigView> models
    ) {
    }

    public record ModelSettings(
            String mode,
            String client,
            String defaultModel,
            String memoryModel,
            String planner
    ) {
    }

    public record ModelConfigView(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            boolean apiKeyConfigured,
            double temperature,
            int timeoutSeconds
    ) {
    }

    public record EmbeddingConfigView(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            boolean apiKeyConfigured,
            int dimensions,
            int timeoutSeconds
    ) {
    }

    /**
     * 候选记忆提炼配置视图。
     *
     * @param enabled 是否启用候选记忆提炼。
     * @param mode 处理策略：after-task-async 或 batch。
     * @param intervalSeconds 定时批处理间隔秒数。
     * @param batchSize 单次后台批处理数量。
     */
    public record MemoryExtractionConfigView(
            boolean enabled,
            String mode,
            long intervalSeconds,
            int batchSize
    ) {
    }

    public record ModelConfigUpdate(
            String mode,
            String client,
            String defaultModel,
            String memoryModel,
            String planner,
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            Double temperature,
            Integer timeoutSeconds,
            String embeddingProvider,
            String embeddingBaseUrl,
            String embeddingModel,
            String embeddingApiKey,
            Integer embeddingDimensions,
            Integer embeddingTimeoutSeconds,
            Boolean memoryExtractionEnabled,
            String memoryExtractionMode,
            Long memoryExtractionIntervalSeconds,
            Integer memoryExtractionBatchSize
    ) {
    }

    /**
     * 新增或更新模型池中的一个模型。
     *
     * @param id 模型配置 ID，用于 default-model 或 memory-model 引用。
     * @param provider 模型供应商标识，例如 deepseek、siliconflow。
     * @param baseUrl OpenAI-compatible API 根地址。
     * @param model 供应商真实模型名。
     * @param apiKey API Key 明文；只写入本地覆盖配置，不写日志。
     * @param temperature 采样温度。
     * @param timeoutSeconds 请求超时秒数。
     */
    public record ModelConfigUpsertRequest(
            String id,
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            Double temperature,
            Integer timeoutSeconds
    ) {
    }

    /**
     * 模型在线测试请求。
     *
     * @param provider 模型供应商标识，仅用于页面展示，测试请求按 baseUrl 调用。
     * @param baseUrl OpenAI-compatible API 根地址。
     * @param model 供应商真实模型名。
     * @param apiKey API Key 明文；只用于本次测试，不持久化。
     * @param prompt 测试提示词。
     * @param temperature 采样温度。
     * @param timeoutSeconds 请求超时秒数。
     */
    public record ModelApiTestRequest(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            String prompt,
            Double temperature,
            Integer timeoutSeconds
    ) {
    }

    /**
     * 模型在线测试响应。
     *
     * @param success 是否测试成功。
     * @param statusCode HTTP 状态码；网络异常时为 0。
     * @param message 模型返回摘要或错误说明。
     * @param rawError 错误响应片段，避免页面展示超长原文。
     * @param promptTokens 请求 token 数；供应商未返回时为 0。
     * @param completionTokens 回复 token 数；供应商未返回时为 0。
     * @param totalTokens 总 token 数；供应商未返回时为 0。
     * @param elapsedMs 请求耗时毫秒。
     */
    public record ModelApiTestResponse(
            boolean success,
            int statusCode,
            String message,
            String rawError,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long elapsedMs
    ) {
    }

    /**
     * 向量化状态视图。
     *
     * @param id 文档 ID 或记忆 ID。
     * @param name 文档名称或记忆摘要。
     * @param status 原始业务状态。
     * @param chunkCount 已解析出的 chunk 数。
     * @param vectorCount 已写入向量表的 chunk 数。
     * @param vectorized 是否所有 chunk 都已有向量。
     */
    public record VectorStatusView(
            String id,
            String name,
            String status,
            int chunkCount,
            int vectorCount,
            boolean vectorized
    ) {
    }

    public record AutomationUpsertRequest(
            String name,
            String prompt,
            String sessionId,
            String channelId,
            String userId,
            String scheduleType,
            String cronExpression,
            Long intervalSeconds,
            String timezone,
            Instant nextRunAt,
            String status,
            Map<String, String> metadata
    ) {
    }

    /**
     * 附件解析 HTTP 响应。
     *
     * @param attachments 上传附件的轻量解析结果，不包含附件正文。
     */
    public record AttachmentParseResponse(
            List<AttachmentParseResult> attachments
    ) {
    }

    /**
     * 知识库检索 HTTP 请求。
     *
     * @param userId 当前用户 ID，用于知识库检索隔离。
     * @param query 检索关键词或自然语言问题。
     * @param documentIds 限定检索的文档 ID；为空时按当前用户全库检索。
     * @param mode 检索模式，例如 keyword、vector、hybrid。
     * @param topK 返回 chunk 数量上限。
     */
    public record KnowledgeSearchPayload(
            String userId,
            String query,
            List<String> documentIds,
            String mode,
            Integer topK
    ) {
    }

    /**
     * 知识库检索 HTTP 响应。
     *
     * @param hits 检索命中的 chunk 列表。
     */
    public record KnowledgeSearchResponse(
            List<KnowledgeSearchResult> hits
    ) {
    }

    /**
     * 记忆检索 HTTP 请求。
     *
     * @param userId 当前用户 ID，所有记忆检索必须按用户隔离。
     * @param query 检索关键词或自然语言问题。
     * @param scopeTypes 允许检索的范围，默认 global/channel/session。
     * @param scopeId 指定范围 ID，例如 channelId 或 sessionId。
     * @param statuses 允许检索的状态；模型上下文默认只使用 active。
     * @param mode 检索模式：keyword、vector、hybrid。
     * @param topK 返回命中数量上限。
     */
    public record MemorySearchPayload(
            String userId,
            String query,
            List<String> scopeTypes,
            String scopeId,
            List<String> statuses,
            String mode,
            Integer topK
    ) {
    }

    /**
     * 记忆检索 HTTP 响应。
     *
     * @param hits 记忆检索命中的片段列表。
     */
    public record MemorySearchResponse(
            List<MemorySearchHit> hits
    ) {
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

    private RuntimeConfigSnapshot buildRuntimeConfigSnapshot(String message, boolean restartRequired, boolean applied) {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path configRoot = runtimeConfigRoot();
        Path configPath = runtimeConfigPath();
        Map<String, ModelConfigView> modelViews = new LinkedHashMap<>();
        for (Map.Entry<String, ClawAgentProperties.ModelConfig> entry : properties.getModels().entrySet()) {
            modelViews.put(entry.getKey(), toModelConfigView(entry.getValue()));
        }
        String defaultModel = properties.getModel().getDefault();
        ClawAgentProperties.ModelConfig effective = properties.getModels().get(defaultModel);
        if (effective == null) {
            effective = ensureModelConfig(defaultModel);
            modelViews.put(defaultModel, toModelConfigView(effective));
        }
        ModelSettings modelSettings = new ModelSettings(
                properties.getModel().getMode(),
                properties.getModel().getClient(),
                defaultModel,
                properties.getModel().getMemoryModel(),
                properties.getModel().getPlanner()
        );
        return new RuntimeConfigSnapshot(
                cwd.toString(),
                configRoot.toString(),
                configPath.toString(),
                restartRequired,
                applied,
                message,
                modelSettings,
                toModelConfigView(effective),
                toEmbeddingConfigView(properties.getMemory().getVector().getEmbedding()),
                toMemoryExtractionConfigView(properties.getMemory().getExtraction()),
                modelViews
        );
    }

    private void applyModelConfigUpdate(ModelConfigUpdate update) {
        String defaultModel = firstNonBlank(update.defaultModel(), properties.getModel().getDefault());
        if (defaultModel == null || defaultModel.isBlank()) {
            defaultModel = firstNonBlank(update.model(), "default");
        }
        // 页面保存的是本地覆盖配置；真正的 ModelClient Bean 仍需服务重启后重新创建。
        properties.getModel().setMode(firstNonBlank(update.mode(), properties.getModel().getMode()));
        properties.getModel().setClient(firstNonBlank(update.client(), properties.getModel().getClient()));
        properties.getModel().setDefault(defaultModel);
        properties.getModel().setMemoryModel(firstNonBlank(update.memoryModel(), properties.getModel().getMemoryModel()));
        properties.getModel().setPlanner(firstNonBlank(update.planner(), properties.getModel().getPlanner()));

        ClawAgentProperties.ModelConfig config = ensureModelConfig(defaultModel);
        config.setProvider(firstNonBlank(update.provider(), config.getProvider()));
        config.setBaseUrl(firstNonBlank(update.baseUrl(), config.getBaseUrl()));
        config.setModel(firstNonBlank(update.model(), firstNonBlank(config.getModel(), defaultModel)));
        config.setApiKey(firstNonBlank(update.apiKey(), config.getApiKey()));
        if (update.temperature() != null) {
            config.setTemperature(update.temperature());
        }
        if (update.timeoutSeconds() != null) {
            config.setTimeoutSeconds(update.timeoutSeconds());
        }
        String memoryModel = properties.getModel().getMemoryModel();
        if (memoryModel != null && !memoryModel.isBlank() && !memoryModel.equals(defaultModel)) {
            ClawAgentProperties.ModelConfig memoryConfig = ensureModelConfig(memoryModel);
            if ("siliconflow-qwen3-8b".equals(memoryModel)) {
                memoryConfig.setProvider("siliconflow");
                memoryConfig.setBaseUrl("https://api.siliconflow.cn/v1");
                memoryConfig.setModel("Qwen/Qwen3-8B");
                memoryConfig.setApiKey(firstNonBlank(memoryConfig.getApiKey(), config.getApiKey()));
                memoryConfig.setTemperature(0.2);
                memoryConfig.setTimeoutSeconds(60);
            } else {
                // 自定义记忆模型未单独编辑时，先复用聊天模型连接，保证保存后有可用配置。
                memoryConfig.setProvider(firstNonBlank(memoryConfig.getProvider(), config.getProvider()));
                memoryConfig.setBaseUrl(firstNonBlank(memoryConfig.getBaseUrl(), config.getBaseUrl()));
                memoryConfig.setModel(firstNonBlank(memoryConfig.getModel(), memoryModel));
                memoryConfig.setApiKey(firstNonBlank(memoryConfig.getApiKey(), config.getApiKey()));
                memoryConfig.setTemperature(memoryConfig.getTemperature());
                memoryConfig.setTimeoutSeconds(memoryConfig.getTimeoutSeconds());
            }
        }

        ClawAgentProperties.Embedding embedding = properties.getMemory().getVector().getEmbedding();
        embedding.setProvider(firstNonBlank(update.embeddingProvider(), embedding.getProvider()));
        embedding.setBaseUrl(firstNonBlank(update.embeddingBaseUrl(), embedding.getBaseUrl()));
        embedding.setModel(firstNonBlank(update.embeddingModel(), embedding.getModel()));
        embedding.setApiKey(firstNonBlank(update.embeddingApiKey(), embedding.getApiKey()));
        if (update.embeddingDimensions() != null) {
            embedding.setDimensions(update.embeddingDimensions());
        }
        if (update.embeddingTimeoutSeconds() != null) {
            embedding.setTimeoutSeconds(update.embeddingTimeoutSeconds());
        }
        ClawAgentProperties.Extraction extraction = properties.getMemory().getExtraction();
        if (update.memoryExtractionEnabled() != null) {
            extraction.setEnabled(update.memoryExtractionEnabled());
        }
        if (update.memoryExtractionMode() != null && !update.memoryExtractionMode().isBlank()) {
            extraction.setMode(update.memoryExtractionMode());
        }
        if (update.memoryExtractionIntervalSeconds() != null) {
            extraction.setIntervalSeconds(update.memoryExtractionIntervalSeconds());
        }
        if (update.memoryExtractionBatchSize() != null) {
            extraction.setBatchSize(update.memoryExtractionBatchSize());
        }
    }

    private ClawAgentProperties.ModelConfig ensureModelConfig(String key) {
        return properties.getModels().computeIfAbsent(key, ignored -> {
            ClawAgentProperties.ModelConfig config = new ClawAgentProperties.ModelConfig();
            config.setModel(key);
            return config;
        });
    }

    private ModelConfigView toModelConfigView(ClawAgentProperties.ModelConfig config) {
        String apiKey = config.getApiKey();
        String apiKeyEnv = config.getApiKeyEnv();
        boolean apiKeyConfigured = (apiKey != null && !apiKey.isBlank())
                || (apiKeyEnv != null && !apiKeyEnv.isBlank() && System.getenv(apiKeyEnv) != null);
        return new ModelConfigView(
                config.getProvider(),
                config.getBaseUrl(),
                config.getModel(),
                apiKey,
                apiKeyConfigured,
                config.getTemperature(),
                config.getTimeoutSeconds()
        );
    }

    private EmbeddingConfigView toEmbeddingConfigView(ClawAgentProperties.Embedding config) {
        String apiKey = config.getApiKey();
        String apiKeyEnv = config.getApiKeyEnv();
        boolean apiKeyConfigured = (apiKey != null && !apiKey.isBlank())
                || (apiKeyEnv != null && !apiKeyEnv.isBlank() && System.getenv(apiKeyEnv) != null);
        return new EmbeddingConfigView(
                config.getProvider(),
                config.getBaseUrl(),
                config.getModel(),
                apiKey,
                apiKeyConfigured,
                config.getDimensions(),
                config.getTimeoutSeconds()
        );
    }

    private MemoryExtractionConfigView toMemoryExtractionConfigView(ClawAgentProperties.Extraction config) {
        return new MemoryExtractionConfigView(
                config.isEnabled(),
                config.getMode(),
                config.getIntervalSeconds(),
                config.getBatchSize()
        );
    }

    private void writeRuntimeConfigFile() {
        Path configPath = runtimeConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, renderModelConfigYaml(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("保存模型配置失败：" + ex.getMessage(), ex);
        }
        log.warn("model config saved configPath={} restartRequired=true", configPath);
    }

    private String renderModelConfigYaml() {
        String defaultModel = properties.getModel().getDefault();
        ensureModelConfig(defaultModel);
        StringBuilder yaml = new StringBuilder();
        yaml.append("# ClawAgent local runtime override.\n");
        yaml.append("# This file is generated by admin console. Model client changes require service restart.\n");
        yaml.append("clawagent:\n");
        yaml.append("  model:\n");
        yaml.append("    mode: ").append(yamlScalar(properties.getModel().getMode())).append('\n');
        yaml.append("    client: ").append(yamlScalar(properties.getModel().getClient())).append('\n');
        yaml.append("    default-model: ").append(yamlScalar(defaultModel)).append('\n');
        if (properties.getModel().getMemoryModel() != null && !properties.getModel().getMemoryModel().isBlank()) {
            yaml.append("    memory-model: ").append(yamlScalar(properties.getModel().getMemoryModel())).append('\n');
        }
        yaml.append("    planner: ").append(yamlScalar(properties.getModel().getPlanner())).append('\n');
        yaml.append("  models:\n");
        for (Map.Entry<String, ClawAgentProperties.ModelConfig> entry : properties.getModels().entrySet()) {
            ClawAgentProperties.ModelConfig modelConfig = entry.getValue();
            yaml.append("    ").append(yamlKey(entry.getKey())).append(":\n");
            yaml.append("      provider: ").append(yamlScalar(modelConfig.getProvider())).append('\n');
            yaml.append("      base-url: ").append(yamlScalar(modelConfig.getBaseUrl())).append('\n');
            yaml.append("      model: ").append(yamlScalar(modelConfig.getModel())).append('\n');
            yaml.append("      api-key: ").append(yamlScalar(modelConfig.getApiKey())).append('\n');
            yaml.append("      temperature: ").append(modelConfig.getTemperature()).append('\n');
            yaml.append("      timeout-seconds: ").append(modelConfig.getTimeoutSeconds()).append('\n');
        }
        ClawAgentProperties.Embedding embedding = properties.getMemory().getVector().getEmbedding();
        yaml.append("  memory:\n");
        ClawAgentProperties.Extraction extraction = properties.getMemory().getExtraction();
        yaml.append("    extraction:\n");
        yaml.append("      enabled: ").append(extraction.isEnabled()).append('\n');
        yaml.append("      mode: ").append(yamlScalar(extraction.getMode())).append('\n');
        yaml.append("      interval-seconds: ").append(extraction.getIntervalSeconds()).append('\n');
        yaml.append("      batch-size: ").append(extraction.getBatchSize()).append('\n');
        yaml.append("    vector:\n");
        yaml.append("      enabled: true\n");
        yaml.append("      provider: jvector\n");
        yaml.append("      embedding:\n");
        yaml.append("        provider: ").append(yamlScalar(embedding.getProvider())).append('\n');
        yaml.append("        base-url: ").append(yamlScalar(embedding.getBaseUrl())).append('\n');
        yaml.append("        model: ").append(yamlScalar(embedding.getModel())).append('\n');
        yaml.append("        api-key: ").append(yamlScalar(embedding.getApiKey())).append('\n');
        yaml.append("        dimensions: ").append(embedding.getDimensions()).append('\n');
        yaml.append("        timeout-seconds: ").append(embedding.getTimeoutSeconds()).append('\n');
        return yaml.toString();
    }

    private List<VectorStatusView> queryVectorStatus(
            String ownerTable,
            String chunkTable,
            String vectorTable,
            String ownerForeignKey,
            String ownerIdColumn,
            String ownerNameColumn,
            String ownerStatusColumn,
            String userId) {
        Path databasePath = runtimeConfigRoot().resolve("clawagent.db");
        if (!Files.exists(databasePath)) {
            return List.of();
        }
        String sql = "select o." + ownerIdColumn + " as id, o." + ownerNameColumn + " as name, o." + ownerStatusColumn + " as status, " +
                "count(distinct c.id) as chunk_count, count(distinct v.chunk_id) as vector_count " +
                "from " + ownerTable + " o " +
                "left join " + chunkTable + " c on c." + ownerForeignKey + " = o." + ownerIdColumn + " and c.user_id = o.user_id " +
                "left join " + vectorTable + " v on v.chunk_id = c.id and v.user_id = o.user_id " +
                "where o.user_id = ? " +
                "group by o." + ownerIdColumn + ", o." + ownerNameColumn + ", o." + ownerStatusColumn + " " +
                "order by o." + ownerIdColumn + " desc";
        List<VectorStatusView> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstNonBlank(userId, "console"));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    int chunkCount = rs.getInt("chunk_count");
                    int vectorCount = rs.getInt("vector_count");
                    // chunk 为 0 说明尚未解析成功，不能算已向量化；vector 必须覆盖所有 chunk。
                    boolean vectorized = chunkCount > 0 && vectorCount >= chunkCount;
                    rows.add(new VectorStatusView(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("status"),
                            chunkCount,
                            vectorCount,
                            vectorized
                    ));
                }
            }
        } catch (Exception ex) {
            log.warn("vector status query skipped table={} reason={}", ownerTable, ex.getMessage());
        }
        return rows;
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String resolveInlineApiKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.startsWith("${") && key.endsWith("}") && key.length() > 3) {
            // 页面可能保存 Spring 占位符；在线测试没有走 Spring Binder，需要在这里解析当前进程环境变量。
            return firstNonBlank(System.getenv(key.substring(2, key.length() - 1)), "");
        }
        return key;
    }

    private Path runtimeConfigPath() {
        return runtimeConfigRoot().resolve("config").resolve("clawagent.yml");
    }

    private Path runtimeConfigRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path current = cwd; current != null; current = current.getParent()) {
            Path candidate = current.resolve(".clawagent");
            // 优先复用仓库级 .clawagent，避免从子模块启动时误写 claw-agent-server/.clawagent。
            if (Files.isDirectory(candidate)
                    && (Files.exists(candidate.resolve("clawagent.db")) || Files.isDirectory(candidate.resolve("skills")))) {
                return candidate;
            }
        }
        return cwd.resolve(".clawagent");
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }

    private String yamlKey(String value) {
        String key = value == null || value.isBlank() ? "default" : value;
        return key.matches("[A-Za-z0-9_.-]+") ? key : yamlScalar(key);
    }

    private String yamlScalar(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String detectKnowledgeKind(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        String name = filename == null ? "" : filename.toLowerCase();
        if (type.startsWith("image/")) {
            return "image";
        }
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            return "pdf";
        }
        if (type.contains("word") || name.endsWith(".doc") || name.endsWith(".docx")) {
            return "word";
        }
        if (type.contains("excel") || type.contains("spreadsheet") || name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".csv")) {
            return "excel";
        }
        return "text";
    }

    private AutomationDefinition buildAutomation(String id, AutomationUpsertRequest request, AutomationDefinition existing) {
        Instant now = Instant.now();
        AutomationScheduleType scheduleType = parseScheduleType(firstNonBlank(
                request.scheduleType(),
                existing == null ? AutomationScheduleType.INTERVAL.name() : existing.scheduleType().name()));
        AutomationStatus status = parseAutomationStatus(firstNonBlank(
                request.status(),
                existing == null ? AutomationStatus.ENABLED.name() : existing.status().name()));
        String prompt = firstNonBlank(request.prompt(), existing == null ? null : existing.prompt());
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("自动化任务缺少 prompt");
        }
        String sessionId = firstNonBlank(request.sessionId(), existing == null ? null : existing.sessionId());
        if (sessionId == null || sessionId.isBlank()) {
            // 新建自动化时只预分配会话 ID，不落盘会话内容；首次执行时 Runtime 会用该 ID 创建持久化会话。
            sessionId = runtime.createSessionId();
        }

        AutomationDefinition automation = new AutomationDefinition(
                id,
                firstNonBlank(request.name(), existing == null ? "自动化任务" : existing.name()),
                prompt,
                sessionId,
                firstNonBlank(request.channelId(), existing == null ? properties.getAutomation().getDefaultChannelId() : existing.channelId()),
                firstNonBlank(request.userId(), existing == null ? properties.getAutomation().getDefaultUserId() : existing.userId()),
                scheduleType,
                firstNonBlank(request.cronExpression(), existing == null ? null : existing.cronExpression()),
                request.intervalSeconds() == null ? existing == null ? null : existing.intervalSeconds() : request.intervalSeconds(),
                firstNonBlank(request.timezone(), existing == null ? "Asia/Shanghai" : existing.timezone()),
                request.nextRunAt() == null ? existing == null ? null : existing.nextRunAt() : request.nextRunAt(),
                existing == null ? null : existing.lastRunAt(),
                status,
                request.metadata() == null ? existing == null ? Map.of() : existing.metadata() : request.metadata(),
                existing == null ? now : existing.createdAt(),
                now);
        // 保存前补齐 nextRunAt，调度器只需要扫描到期任务，不再猜测用户意图。
        return status == AutomationStatus.ENABLED && automation.nextRunAt() == null
                ? automationSchedulerService.refreshNextRun(automation, now)
                : automation;
    }

    private AutomationDefinition copyAutomationWithStatus(AutomationDefinition automation, AutomationStatus status, Instant updatedAt) {
        return copyAutomationWithNextRun(automation, status, automation.nextRunAt(), updatedAt);
    }

    private AutomationDefinition copyAutomationWithNextRun(
            AutomationDefinition automation,
            AutomationStatus status,
            Instant nextRunAt,
            Instant updatedAt) {
        return new AutomationDefinition(
                automation.id(),
                automation.name(),
                automation.prompt(),
                automation.sessionId(),
                automation.channelId(),
                automation.userId(),
                automation.scheduleType(),
                automation.cronExpression(),
                automation.intervalSeconds(),
                automation.timezone(),
                nextRunAt,
                automation.lastRunAt(),
                status,
                automation.metadata(),
                automation.createdAt(),
                updatedAt);
    }

    private AutomationScheduleType parseScheduleType(String value) {
        try {
            return AutomationScheduleType.valueOf(firstNonBlank(value, AutomationScheduleType.INTERVAL.name()).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的自动化调度类型：" + value, ex);
        }
    }

    private AutomationStatus parseAutomationStatus(String value) {
        try {
            return AutomationStatus.valueOf(firstNonBlank(value, AutomationStatus.ENABLED.name()).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的自动化状态：" + value, ex);
        }
    }

    private String preview(String text) {
        return preview(text, 120);
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizeStreamContent(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return content.replaceFirst("(?i)^(null)+", "");
    }
}
