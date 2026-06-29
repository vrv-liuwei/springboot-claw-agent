package com.github.clawagent.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.server.service.SystemLogQueryService;
import com.github.clawagent.spring.ClawAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 管理台日志接口。
 * 日志属于运维诊断能力，和 Agent 任务执行链路分开，避免继续膨胀管理台主链路服务。
 */
@RestController
@RequestMapping("/api/v1")
public class LogController {
    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private final SystemLogQueryService systemLogQueryService;
    private final ClawAgentProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 日志 tail 是长连接，单独隔离线程池，避免占用任务流式响应线程。 */
    private final ExecutorService logStreamExecutor = Executors.newCachedThreadPool();

    public LogController(SystemLogQueryService systemLogQueryService, ClawAgentProperties properties) {
        this.systemLogQueryService = systemLogQueryService;
        this.properties = properties;
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

    @GetMapping(value = "/logs/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter tailLogs(
            @RequestParam(name = "intervalMillis", defaultValue = "1000") long intervalMillis,
            @RequestParam(name = "maxChars", defaultValue = "20000") int maxChars) {
        SseEmitter emitter = new SseEmitter(0L);
        logStreamExecutor.submit(() -> {
            Path logPath = systemLogQueryService.currentLogPath();
            long lastSize = safeSize(logPath);
            long safeInterval = Math.min(Math.max(intervalMillis, 500), 5000);
            int safeMaxChars = Math.min(Math.max(maxChars, 1000), 100000);
            try {
                sendSse(emitter, "log.ready", Map.of("path", logPath.toString()));
                while (true) {
                    TimeUnit.MILLISECONDS.sleep(safeInterval);
                    long size = safeSize(logPath);
                    if (size > lastSize) {
                        String chunk = tailFile(logPath, safeMaxChars);
                        sendSse(emitter, "log.chunk", Map.of(
                                "path", logPath.toString(),
                                "size", String.valueOf(size),
                                "content", chunk));
                    }
                    lastSize = size;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            Path logPath = systemLogQueryService.currentLogPath();
            if (Files.exists(logPath)) {
                zip.putNextEntry(new ZipEntry("logs/clawagent.log"));
                zip.write(tailFile(logPath, 1_000_000).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("runtime-summary.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "mode", nullToEmpty(properties.getMode()),
                    "javaVersion", System.getProperty("java.version", ""),
                    "osName", System.getProperty("os.name", ""),
                    "generatedAt", Instant.now().toString())));
            zip.closeEntry();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("clawagent-diagnostics.zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(buffer.toByteArray());
    }

    @PostMapping("/client-errors")
    public Map<String, String> recordClientError(@RequestBody ClientErrorPayload payload) {
        ClientErrorPayload safePayload = payload == null ? new ClientErrorPayload("", "", "", "", "", "") : payload;
        log.warn("client error route={} sessionId={} taskId={} message={} stack={}",
                preview(safePayload.route(), 200),
                preview(safePayload.sessionId(), 120),
                preview(safePayload.taskId(), 120),
                preview(safePayload.message(), 800),
                preview(safePayload.stack(), 2000));
        return Map.of("status", "recorded");
    }

    private long safeSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            // tail 接口不能因为文件轮转瞬间读不到 size 就断开，下一轮会继续探测。
            return 0L;
        }
    }

    private String tailFile(Path path, int maxChars) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
        } catch (IOException e) {
            throw new IllegalStateException("读取日志失败：" + path, e);
        }
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ClientErrorPayload(
            String route,
            String message,
            String stack,
            String userAgent,
            String sessionId,
            String taskId
    ) {
    }
}
