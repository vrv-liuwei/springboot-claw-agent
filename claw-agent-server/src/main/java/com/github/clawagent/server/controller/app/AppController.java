package com.github.clawagent.server.controller.app;

import com.github.clawagent.server.service.AppWorkspaceService;
import com.github.clawagent.spring.ClawAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AppController 只承载 App 特有能力。
 * 通用 agent、会话、任务、日志能力仍复用 /api/v1 现有入口。
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppController {
    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private final AppWorkspaceService workspaceService;
    private final ClawAgentProperties properties;
    private final String serverPort;
    private final String logFileName;

    public AppController(AppWorkspaceService workspaceService,
                         ClawAgentProperties properties,
                         @Value("${server.port:8080}") String serverPort,
                         @Value("${logging.file.name:logs/clawagent.log}") String logFileName) {
        this.workspaceService = workspaceService;
        this.properties = properties;
        this.serverPort = serverPort;
        this.logFileName = logFileName;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Path logPath = Path.of(logFileName).toAbsolutePath().normalize();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode());
        result.put("serverPort", serverPort);
        result.put("appPath", "/app/");
        result.put("appUrl", "http://127.0.0.1:" + serverPort + "/app/");
        result.put("logDir", logPath.getParent() == null ? "" : logPath.getParent().toString());
        result.put("dataDir", workspaceService.dataDir().toString());
        result.put("platform", platform());
        result.put("javaVersion", System.getProperty("java.version", ""));
        result.put("localAccess", isLocalMode());
        return result;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        boolean localSystem = isLocalMode();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceManage", localSystem);
        result.put("openLocalPath", localSystem);
        result.put("revealPath", localSystem);
        result.put("revealLogDir", localSystem);
        result.put("mode", mode());
        result.put("serverMode", mode());
        return result;
    }

    @PostMapping("/workspaces/open")
    public AppWorkspaceService.AppWorkspace openWorkspace(@RequestBody WorkspacePathRequest request) {
        requireLocalSystem();
        return workspaceService.openWorkspace(requiredPath(request == null ? null : request.path()));
    }

    @GetMapping("/workspaces/recent")
    public List<AppWorkspaceService.AppWorkspace> recentWorkspaces() {
        return workspaceService.recentWorkspaces();
    }

    @PostMapping("/workspaces/switch")
    public AppWorkspaceService.AppWorkspace switchWorkspace(@RequestBody WorkspaceSwitchRequest request) {
        requireLocalSystem();
        if (request == null || request.workspaceId() == null || request.workspaceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId 不能为空");
        }
        return workspaceService.switchWorkspace(request.workspaceId());
    }

    @PatchMapping("/workspaces/{workspaceId}")
    public AppWorkspaceService.AppWorkspace renameWorkspace(@PathVariable String workspaceId,
                                                            @RequestBody WorkspaceRenameRequest request) {
        requireLocalSystem();
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId 不能为空");
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 不能为空");
        }
        return workspaceService.renameWorkspace(workspaceId, request.name());
    }

    @GetMapping("/workspaces/current")
    public AppWorkspaceService.AppWorkspace currentWorkspace() {
        return workspaceService.currentWorkspace().orElse(null);
    }

    @PostMapping("/system/open-path")
    public Map<String, String> openPath(@RequestBody WorkspacePathRequest request) {
        requireLocalSystem();
        Path path = existingPath(requiredPath(request == null ? null : request.path()));
        requireRegisteredPath(path);
        launchOpen(path, false);
        return Map.of("path", path.toString(), "action", "open");
    }

    @PostMapping("/system/reveal-path")
    public Map<String, String> revealPath(@RequestBody WorkspacePathRequest request) {
        requireLocalSystem();
        Path path = existingPath(requiredPath(request == null ? null : request.path()));
        requireRegisteredPath(path);
        launchOpen(path, true);
        return Map.of("path", path.toString(), "action", "reveal");
    }

    @PostMapping("/system/reveal-log-dir")
    public Map<String, String> revealLogDir() {
        requireLocalSystem();
        Path logPath = Path.of(logFileName).toAbsolutePath().normalize();
        Path dir = Files.isDirectory(logPath) ? logPath : logPath.getParent();
        if (dir == null) {
            dir = Path.of("logs").toAbsolutePath().normalize();
        }
        launchOpen(dir, false);
        return Map.of("path", dir.toString(), "action", "reveal-log-dir");
    }

    private void requireLocalSystem() {
        if (!isLocalMode()) {
            // 非 local 模式禁止本机文件系统能力，记录 WARN 方便 App 日志页定位 403。
            log.warn("local system operation rejected mode={} status=403 reason={}",
                    mode(), "当前模式不允许本地系统操作");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前模式不允许本地系统操作");
        }
    }

    private void requireRegisteredPath(Path path) {
        if (!workspaceService.isRegisteredPath(path)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能打开已登记 workspace 内的路径");
        }
    }

    private String requiredPath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        return path;
    }

    private Path existingPath(String rawPath) {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "路径不存在：" + path);
        }
        return path;
    }

    private void launchOpen(Path path, boolean reveal) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                if (reveal && Files.isRegularFile(path)) {
                    new ProcessBuilder("explorer", "/select,", path.toString()).start();
                } else {
                    new ProcessBuilder("explorer", path.toString()).start();
                }
            } else if (os.contains("mac")) {
                if (reveal) {
                    new ProcessBuilder("open", "-R", path.toString()).start();
                } else {
                    new ProcessBuilder("open", path.toString()).start();
                }
            } else {
                Path target = reveal && Files.isRegularFile(path) ? path.getParent() : path;
                new ProcessBuilder("xdg-open", target == null ? path.toString() : target.toString()).start();
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "打开系统路径失败：" + e.getMessage(), e);
        }
    }

    private String mode() {
        String mode = properties.getMode();
        return mode == null || mode.isBlank() ? "server" : mode;
    }

    private boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode());
    }

    private String platform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    public record WorkspacePathRequest(String path) {
    }

    public record WorkspaceSwitchRequest(String workspaceId) {
    }

    public record WorkspaceRenameRequest(String name) {
    }
}
