package com.github.clawagent.server.service;

import com.github.clawagent.toolkit.ToolkitRegistry;
import com.github.clawagent.toolkit.execute.CommandOutputDecoder;
import com.github.clawagent.toolkit.process.ManagedProcess;
import com.github.clawagent.toolkit.process.ManagedProcessStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 托管后台进程的查询、诊断和停止服务。
 * Controller 和任务开发摘要共用这层逻辑，避免同一套启动失败判断散落在多个接口里。
 */
@Service
public class ProcessManagementService {
    private static final Pattern PORT_PATTERN = Pattern.compile("(?i)(?:port|端口|localhost:|127\\.0\\.0\\.1:|0\\.0\\.0\\.0:|:)(\\d{2,5})");

    private final ToolkitRegistry toolkitRegistry;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ProcessManagementService(ToolkitRegistry toolkitRegistry) {
        this.toolkitRegistry = toolkitRegistry;
    }

    public List<ProcessView> processes(int logChars) {
        int maxChars = Math.min(Math.max(logChars, 0), 20000);
        return processStore().list().stream()
                .map(process -> toProcessView(process, maxChars))
                .sorted(Comparator.comparing(ProcessView::startedAt).reversed())
                .toList();
    }

    public ProcessLogsView processLogs(long pid, int maxChars) {
        ManagedProcess process = processStore().get(pid)
                .orElseThrow(() -> new IllegalArgumentException("未找到托管进程：" + pid));
        String logs = tailProcessLog(process, Math.min(Math.max(maxChars, 0), 50000));
        List<Integer> ports = detectPorts(String.join(" ", process.command()) + "\n" + logs);
        Map<Integer, Boolean> portStatus = probePorts(ports);
        ProcessHealthView health = probeProcessHealth(process.healthUrl());
        return new ProcessLogsView(pid, process.logPath().toString(), process.isAlive() ? "running" : "exited", logs, health,
                diagnoseProcess(process, logs, ports, portStatus, health));
    }

    public ProcessView stopProcess(long pid, boolean force) {
        ManagedProcess process = processStore().get(pid)
                .orElseThrow(() -> new IllegalArgumentException("未找到托管进程：" + pid));
        boolean aliveAfterStop = stopProcessTree(process, force);
        if (!aliveAfterStop) {
            processStore().remove(pid);
        }
        return toProcessView(process, 4000);
    }

    public List<ProcessView> processesForTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        // 任务摘要只展示与本任务直接关联的托管进程，避免把其他会话的服务混进来。
        return processStore().list().stream()
                .filter(process -> taskId.equals(process.taskId()))
                .sorted(Comparator.comparing(ManagedProcess::startedAt).reversed())
                .map(process -> toProcessView(process, 3000))
                .toList();
    }

    private ManagedProcessStore processStore() {
        return toolkitRegistry.processStore();
    }

    private ProcessView toProcessView(ManagedProcess process, int logChars) {
        String logTail = logChars <= 0 ? "" : tailProcessLog(process, logChars);
        String commandLine = String.join(" ", process.command());
        List<Integer> ports = detectPorts(commandLine + "\n" + logTail);
        Map<Integer, Boolean> portStatus = probePorts(ports);
        ProcessHealthView health = probeProcessHealth(process.healthUrl());
        ProcessDiagnosisView diagnosis = diagnoseProcess(process, logTail, ports, portStatus, health);
        return new ProcessView(
                process.pid(),
                process.isAlive() ? "running" : "exited",
                process.command(),
                commandLine,
                process.cwd().toString(),
                process.logPath().toString(),
                process.startedAt(),
                ports,
                portStatus,
                process.process() == null,
                processStore().persistencePath().toString(),
                logTail,
                process.taskId(),
                process.sessionId(),
                process.projectPath(),
                health,
                diagnosis);
    }

    private ProcessDiagnosisView diagnoseProcess(ManagedProcess process, String logs, List<Integer> ports, Map<Integer, Boolean> portStatus, ProcessHealthView health) {
        String text = (String.join(" ", process.command()) + "\n" + nullToEmpty(logs)).toLowerCase(Locale.ROOT);
        List<String> evidence = processEvidence(logs);
        // 端口探测已经在进程列表接口完成，这里只消费结果生成用户可读诊断，避免重复探测。
        boolean hasListeningPort = portStatus != null && portStatus.values().stream().anyMatch(Boolean::booleanValue);
        if (process.isAlive() && health != null && "healthy".equals(health.status())) {
            return new ProcessDiagnosisView("healthy", "health-url", "健康检查已通过。", "服务已响应健康检查，可继续验证业务接口。", evidence);
        }
        if (process.isAlive() && health != null && "unhealthy".equals(health.status())) {
            return new ProcessDiagnosisView("unknown", "health-url-unready", "进程仍在运行，但健康检查尚未通过。", "继续查看启动日志，或确认健康检查 URL 是否正确。", evidence);
        }
        if (process.isAlive() && (hasListeningPort || text.contains("started") || text.contains("started ") || text.contains("启动成功"))) {
            return new ProcessDiagnosisView("healthy", "started", "进程仍在运行，日志中未发现明确启动失败。", "继续观察端口和业务日志。", evidence);
        }
        if (text.contains("address already in use") || text.contains("port already in use")
                || (text.contains("端口") && (text.contains("占用") || text.contains("已被使用")))) {
            return new ProcessDiagnosisView("failed", "port-conflict", "启动失败，疑似端口被占用。", "查看端口占用进程，停止旧服务或调整端口后重启。", evidence);
        }
        if (text.contains("failed to configure") || text.contains("configurationproperties")
                || text.contains("could not resolve placeholder") || (text.contains("配置") && text.contains("错误"))) {
            return new ProcessDiagnosisView("failed", "config-error", "启动失败，疑似配置项错误或缺失。", "检查 application 配置、环境变量和启动参数。", evidence);
        }
        if (text.contains("classnotfoundexception") || text.contains("noclassdeffounderror")
                || text.contains("could not resolve dependencies") || text.contains("module not found")
                || text.contains("cannot find module") || text.contains("依赖")) {
            return new ProcessDiagnosisView("failed", "dependency-missing", "启动失败，疑似依赖缺失或版本不匹配。", "先执行依赖安装/构建命令，确认锁文件和仓库源可用。", evidence);
        }
        if (text.contains("access denied") || text.contains("permission denied")
                || text.contains("unauthorized") || text.contains("authentication failed")
                || text.contains("认证") || text.contains("登录失败")) {
            return new ProcessDiagnosisView("failed", "auth-permission", "启动失败，疑似认证或权限问题。", "确认账号凭据、文件权限、端口权限和审批策略。", evidence);
        }
        if (text.contains("no such file") || text.contains("file not found") || text.contains("系统找不到")
                || (text.contains("cannot find") && text.contains("path"))) {
            return new ProcessDiagnosisView("failed", "path-not-found", "启动失败，疑似命令或路径不存在。", "确认 cwd、启动脚本和命令参数是否正确。", evidence);
        }
        if (!process.isAlive()) {
            return new ProcessDiagnosisView("failed", "exited", "进程已退出，日志里没有匹配到明确原因。", "打开完整日志，结合最后一段错误输出继续定位。", evidence);
        }
        return new ProcessDiagnosisView("unknown", "running-unknown", "进程仍在运行，但尚未识别到明确健康信号。", "查看端口监听和最新日志，必要时增加健康检查 URL。", evidence);
    }

    private List<String> processEvidence(String logs) {
        if (logs == null || logs.isBlank()) {
            return List.of();
        }
        List<String> lines = Arrays.stream(logs.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                            || lower.contains("denied") || lower.contains("端口") || lower.contains("错误")
                            || lower.contains("失败") || lower.contains("占用");
                })
                .toList();
        if (lines.isEmpty()) {
            lines = Arrays.stream(logs.split("\\R"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
        }
        return lines.stream()
                .skip(Math.max(0, lines.size() - 5))
                .map(line -> preview(line, 240))
                .toList();
    }

    private String tailProcessLog(ManagedProcess process, int maxChars) {
        if (!Files.isRegularFile(process.logPath())) {
            return "";
        }
        try {
            String content = CommandOutputDecoder.decode(Files.readAllBytes(process.logPath()), "stdout");
            return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
        } catch (IOException e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    private List<Integer> detectPorts(String text) {
        List<Integer> ports = new ArrayList<>();
        Matcher matcher = PORT_PATTERN.matcher(nullToEmpty(text));
        while (matcher.find()) {
            int port = Integer.parseInt(matcher.group(1));
            if (port > 0 && port <= 65535 && !ports.contains(port)) {
                ports.add(port);
            }
            if (ports.size() >= 8) {
                break;
            }
        }
        return ports;
    }

    private Map<Integer, Boolean> probePorts(List<Integer> ports) {
        Map<Integer, Boolean> status = new LinkedHashMap<>();
        for (Integer port : ports) {
            status.put(port, isLocalPortOpen(port));
        }
        return status;
    }

    private ProcessHealthView probeProcessHealth(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String url = rawUrl.trim();
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return new ProcessHealthView(url, "invalid", null, "仅支持 http/https 健康检查 URL");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 400;
            return new ProcessHealthView(url, ok ? "healthy" : "unhealthy", response.statusCode(),
                    ok ? "健康检查通过" : "健康检查返回非成功状态码");
        } catch (Exception e) {
            // 健康探测失败只能说明服务暂未就绪，不能因此判定进程本身启动失败。
            return new ProcessHealthView(url, "unhealthy", null, preview(e.getMessage(), 160));
        }
    }

    private boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean stopProcessTree(ManagedProcess process, boolean force) {
        ProcessHandle handle = process.process() != null
                ? process.process().toHandle()
                : ProcessHandle.of(process.pid()).orElse(null);
        if (handle == null) {
            return false;
        }
        // 先停子进程，再停主进程；npm/mvn/cmd 这类启动器经常会留下实际服务子进程。
        List<ProcessHandle> descendants = handle.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) {
            destroyHandle(descendants.get(i), force);
        }
        destroyHandle(handle, force);
        if (process.process() != null) {
            awaitExit(process.process(), force);
        } else {
            awaitExit(handle, force);
        }
        return handle.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    private void destroyHandle(ProcessHandle handle, boolean force) {
        if (!handle.isAlive()) {
            return;
        }
        if (force) {
            handle.destroyForcibly();
        } else {
            handle.destroy();
        }
    }

    private void awaitExit(Process process, boolean force) {
        try {
            CompletableFuture<Process> exit = process.onExit();
            exit.get(force ? 2 : 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void awaitExit(ProcessHandle handle, boolean force) {
        try {
            handle.onExit().get(force ? 2 : 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
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

    public record ProcessView(
            long pid,
            String status,
            List<String> command,
            String commandLine,
            String cwd,
            String logPath,
            Instant startedAt,
            List<Integer> ports,
            Map<Integer, Boolean> portStatus,
            boolean persistent,
            String storePath,
            String logTail,
            String taskId,
            String sessionId,
            String projectPath,
            ProcessHealthView health,
            ProcessDiagnosisView diagnosis
    ) {
    }

    public record ProcessLogsView(
            long pid,
            String logPath,
            String status,
            String logs,
            ProcessHealthView health,
            ProcessDiagnosisView diagnosis
    ) {
    }

    public record ProcessHealthView(
            String url,
            String status,
            Integer httpStatus,
            String message
    ) {
    }

    public record ProcessDiagnosisView(
            String status,
            String category,
            String summary,
            String nextAction,
            List<String> evidence
    ) {
    }
}
