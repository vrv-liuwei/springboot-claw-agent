package com.github.clawagent.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ApprovalPolicy;
import com.github.clawagent.core.ApprovalPolicyResolution;
import com.github.clawagent.core.PermissionPolicy;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.core.TokenUsageSummary;
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
import com.github.clawagent.skill.ExternalSkillInstallTool;
import com.github.clawagent.skill.SkillPackage;
import com.github.clawagent.skill.SkillRegistration;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.server.service.ProcessManagementService.ProcessView;
import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.dto.ApprovalPolicyView;
import com.github.clawagent.server.dto.AuthConfigView;
import com.github.clawagent.server.dto.AuthRolePolicyView;
import com.github.clawagent.server.dto.CommandRunView;
import com.github.clawagent.server.dto.CostConfigView;
import com.github.clawagent.server.dto.CostRuleView;
import com.github.clawagent.server.dto.DevelopmentTaskSummary;
import com.github.clawagent.server.dto.EmbeddingConfigView;
import com.github.clawagent.server.dto.FailureAnalysisView;
import com.github.clawagent.server.dto.FileChangeView;
import com.github.clawagent.server.dto.FileReviewView;
import com.github.clawagent.server.dto.FinalResultView;
import com.github.clawagent.server.dto.GitReviewView;
import com.github.clawagent.server.dto.LocalDevelopmentConfigView;
import com.github.clawagent.server.dto.LocalHealthItemView;
import com.github.clawagent.server.dto.LocalHealthView;
import com.github.clawagent.server.dto.MemoryExtractionConfigView;
import com.github.clawagent.server.dto.MemoryGovernanceConfigView;
import com.github.clawagent.server.dto.ModelApiTestRequest;
import com.github.clawagent.server.dto.ModelApiTestResponse;
import com.github.clawagent.server.dto.ModelConfigUpdate;
import com.github.clawagent.server.dto.ModelConfigUpsertRequest;
import com.github.clawagent.server.dto.ModelConfigView;
import com.github.clawagent.server.dto.ModelSettings;
import com.github.clawagent.server.dto.OpenTaskFileRequest;
import com.github.clawagent.server.dto.PermissionPolicyView;
import com.github.clawagent.server.dto.PolicyConfigUpdate;
import com.github.clawagent.server.dto.PolicyResolutionLayerView;
import com.github.clawagent.server.dto.PolicySnapshotView;
import com.github.clawagent.server.dto.RecentProjectRequest;
import com.github.clawagent.server.dto.RollbackFileSelectionRequest;
import com.github.clawagent.server.dto.RollbackTaskFileRequest;
import com.github.clawagent.server.dto.RuntimeConfigSnapshot;
import com.github.clawagent.server.dto.TaskAuditApprovalView;
import com.github.clawagent.server.dto.TaskAuditResumeView;
import com.github.clawagent.server.dto.TaskAuditSummaryView;
import com.github.clawagent.server.dto.TaskAuditTimelineView;
import com.github.clawagent.server.dto.TaskAuditToolView;
import com.github.clawagent.server.dto.TaskAuditView;
import com.github.clawagent.server.dto.VerificationCommandView;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.toolkit.ToolkitRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 管理台主链路委托服务。
 * 历史上该类直接承载 HTTP 路由；当前先保留业务编排和私有 helper，新的 controller 只做协议入口。
 */
@Service
public class AgentConsoleService {
    private static final Logger log = LoggerFactory.getLogger(AgentConsoleService.class);
    private static final int FILE_CHANGE_EVENT_SCAN_LIMIT = 5000;

    private final com.github.clawagent.runtime.AgentRuntime runtime;
    private final AgentToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final AppWorkspaceService appWorkspaceService;
    private final ProcessManagementService processManagementService;
    private final ClawAgentProperties properties;
    private final ServerAuthProperties authProperties;
    private final LocalUserService localUserService;
    private final AgentEventStore eventStore;
    /** Service 内部 JSON 工具，只用于模型在线测试和轻量状态接口。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 在线测试模型 API 的短连接客户端，不参与正式 Runtime 调用链路。 */
    private final HttpClient configTestHttpClient = HttpClient.newHttpClient();

    public AgentConsoleService(com.github.clawagent.runtime.AgentRuntime runtime,
                               AgentToolRegistry toolRegistry,
                               McpRegistry mcpRegistry,
                               SkillRegistry skillRegistry,
                               AppWorkspaceService appWorkspaceService,
                               ProcessManagementService processManagementService,
                               ClawAgentProperties properties,
                               ServerAuthProperties authProperties,
                               LocalUserService localUserService,
                               @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.runtime = runtime;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.appWorkspaceService = appWorkspaceService;
        this.processManagementService = processManagementService;
        this.properties = properties;
        this.authProperties = authProperties;
        this.localUserService = localUserService;
        this.eventStore = eventStore;
    }

    private void copyMetadata(AgentTask source, Map<String, String> target, String key) {
        String value = source.metadata().get(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private AgentRequest enrichWorkspace(AgentRequest request) {
        Map<String, String> metadata = appWorkspaceService.enrichWorkspaceMetadata(
                request.workspaceId(),
                request.metadata() == null ? Map.of() : request.metadata());
        return new AgentRequest(request.input(), request.sessionId(), request.channelId(), request.userId(), metadata);
    }

    public RuntimeConfigSnapshot runtimeConfig() {
        return buildRuntimeConfigSnapshot("当前运行配置快照。", false, true);
    }

    public LocalHealthView localHealth(boolean deep) {
        return buildLocalHealthView(deep);
    }

    public RuntimeConfigSnapshot rememberRecentProject(RecentProjectRequest request) {
        String path = request == null ? "" : nullToEmpty(request.path()).trim();
        if (path.isBlank()) {
            throw new IllegalArgumentException("项目目录不能为空");
        }
        Path projectPath = Path.of(path).toAbsolutePath().normalize();
        ClawAgentProperties.Local local = properties.getLocal();
        List<String> recentProjects = new ArrayList<>();
        recentProjects.add(projectPath.toString());
        String normalizedProjectPath = projectPath.toString();
        normalizeConfigList(local.getRecentProjects()).stream()
                .filter(existing -> !normalizedProjectPath.equalsIgnoreCase(normalizeRecentProjectPath(existing)))
                .limit(19)
                .forEach(recentProjects::add);
        // 最近项目是本地部署体验的一部分，保存时只更新 local.recent-projects，避免把配置页未保存草稿一起写入。
        local.setRecentProjects(recentProjects);
        writeRuntimeConfigFile();
        return buildRuntimeConfigSnapshot("最近项目目录已保存。", false, false);
    }

    private String normalizeRecentProjectPath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return nullToEmpty(value).trim();
        }
    }

    public RuntimeConfigSnapshot saveModelConfig(ModelConfigUpdate update) {
        applyModelConfigUpdate(update);
        writeRuntimeConfigFile();
        return buildRuntimeConfigSnapshot("模型配置已保存到本地 YAML，重启服务后生效。", true, false);
    }

    public RuntimeConfigSnapshot savePolicyConfig(PolicyConfigUpdate update) {
        Map<String, String> before = policyAuditSnapshot(properties.getLocal(), "before.");
        applyPolicyConfigUpdate(update);
        writeRuntimeConfigFile();
        recordPolicyConfigUpdated(before, policyAuditSnapshot(properties.getLocal(), "after."));
        return buildRuntimeConfigSnapshot("审批和本地权限策略已保存。", false, false);
    }

    public RuntimeConfigSnapshot upsertModelConfig(ModelConfigUpsertRequest request) {
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
        if (request.vision() != null) {
            config.setVision(request.vision());
        }
        writeRuntimeConfigFile();
        return buildRuntimeConfigSnapshot("模型已加入本地模型池，重启服务后可用于正式调用。", true, false);
    }

    public ModelApiTestResponse testModelApi(ModelApiTestRequest request) {
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

    public List<FileChangeView> taskFileChanges(
            String taskId,
            int limit) {
        log.debug("task file changes requested taskId={} limit={}", taskId, limit);
        return fileChangesForTask(taskId, Math.min(Math.max(limit, 1), 1000));
    }

    public DevelopmentTaskSummary developmentSummary(String taskId) {
        AgentTask task = runtime.getTask(taskId);
        List<AgentEvent> events = runtime.getTaskEvents(taskId, 1000);
        List<FileChangeView> changes = fileChangesForTask(taskId, 1000);
        List<CommandRunView> commands = events.stream()
                .filter(event -> "tool.succeeded".equals(event.type()) || "tool.failed".equals(event.type()))
                .map(this::toCommandRunView)
                .filter(command -> command != null)
                .toList();
        List<CommandRunView> tests = commands.stream()
                .filter(command -> isTestCommand(command.command()))
                .toList();
        List<String> failures = collectDevelopmentFailures(events, commands);
        List<String> risks = collectDevelopmentRisks(commands);
        List<String> suggestions = suggestTestCommands(task, commands);
        List<VerificationCommandView> verificationPlan = buildVerificationPlan(task, commands);
        List<FailureAnalysisView> failureAnalyses = collectFailureAnalyses(events, commands);
        FinalResultView finalResult = buildFinalResult(task, changes, commands, tests, failures, risks, verificationPlan, failureAnalyses);
        GitReviewView gitReview = buildGitReview(task, commands);
        List<ProcessView> processes = processManagementService.processesForTask(task.id());
        return new DevelopmentTaskSummary(
                task.id(),
                task.status().name(),
                changes,
                commands,
                tests,
                failures,
                risks,
                suggestions,
                verificationPlan,
                failureAnalyses,
                finalResult,
                gitReview,
                processes,
                buildCommitMessage(task, changes, tests, failures));
    }

    public TaskAuditView taskAudit(String taskId) {
        AgentTask task = runtime.getTask(taskId);
        List<AgentEvent> events = runtime.getTaskEvents(taskId, 1000);
        List<AgentStep> steps = runtime.getSteps(taskId);
        List<FileChangeView> changes = fileChangesForTask(taskId, 1000);
        List<CommandRunView> commands = events.stream()
                .filter(event -> "tool.succeeded".equals(event.type()) || "tool.failed".equals(event.type()))
                .map(this::toCommandRunView)
                .filter(command -> command != null)
                .toList();
        List<TaskAuditToolView> tools = buildAuditTools(events, steps);
        List<TaskAuditApprovalView> approvals = buildAuditApprovals(events);
        TaskAuditResumeView resume = buildAuditResume(events);
        List<TaskAuditTimelineView> timeline = buildAuditTimeline(events);
        TaskAuditSummaryView summary = new TaskAuditSummaryView(
                tools.size(),
                (int) tools.stream().filter(tool -> "failed".equals(tool.status())).count(),
                approvals.size(),
                (int) approvals.stream().filter(approval -> "granted".equals(approval.status())).count(),
                changes.size(),
                (int) changes.stream().filter(change -> "rollback".equalsIgnoreCase(change.changeType())).count(),
                commands.size(),
                (int) commands.stream().filter(command -> "failed".equals(command.status())
                        || (command.exitCode() != null && command.exitCode() != 0)).count(),
                (int) events.stream().filter(event -> "security.prompt_injection_detected".equals(event.type())).count(),
                events.size());
        return new TaskAuditView(
                task.id(),
                task.sessionId(),
                task.status().name(),
                task.input(),
                summary,
                resume,
                tools,
                approvals,
                changes,
                commands,
                timeline);
    }

    public FileReviewView fileReview(
            String taskId,
            String stepId,
            String path,
            String backupPath) throws IOException {
        FileChangeView change = findFileChange(taskId, stepId, path);
        Path targetPath = Path.of(change.path()).toAbsolutePath().normalize();
        String before = "";
        String after = "";
        if (change.backupPath() != null && !change.backupPath().isBlank()) {
            Path backup = Path.of(backupPath == null || backupPath.isBlank() ? change.backupPath() : backupPath)
                    .toAbsolutePath()
                    .normalize();
            if (!backup.toString().equals(change.backupPath())) {
                throw new IllegalArgumentException("backupPath 与任务变更记录不一致");
            }
            if (Files.exists(backup)) {
                before = readReviewText(backup);
            }
        }
        if (Files.exists(targetPath)) {
            after = readReviewText(targetPath);
        }
        if ("delete".equalsIgnoreCase(change.changeType())) {
            after = "";
        }
        return new FileReviewView(change, before, after);
    }

    public Map<String, String> openTaskFile(
            String taskId,
            OpenTaskFileRequest request) throws IOException {
        FileChangeView change = findFileChange(taskId, nullToEmpty(request.stepId()), nullToEmpty(request.path()));
        Path target = Path.of(change.path()).toAbsolutePath().normalize();
        String action = nullToEmpty(request.action()).toLowerCase();
        if ("vscode".equals(action)) {
            startDetached(List.of("code", "-g", target.toString()));
            return Map.of("status", "started", "action", action, "path", target.toString());
        }
        if ("explorer".equals(action)) {
            Path explorerTarget = Files.exists(target) ? target : target.getParent();
            if (explorerTarget == null) {
                throw new IllegalArgumentException("文件路径无父目录：" + target);
            }
            if (Files.exists(target)) {
                startDetached(List.of("explorer.exe", "/select,", target.toString()));
            } else {
                startDetached(List.of("explorer.exe", explorerTarget.toString()));
            }
            return Map.of("status", "started", "action", action, "path", target.toString());
        }
        throw new IllegalArgumentException("不支持的打开方式：" + request.action());
    }

    public FileReviewView rollbackTaskFile(
            String taskId,
            RollbackTaskFileRequest request) throws IOException {
        FileChangeView change = findFileChange(taskId, nullToEmpty(request.stepId()), nullToEmpty(request.path()));
        if (change.backupPath() == null || change.backupPath().isBlank()) {
            throw new IllegalArgumentException("该文件变更没有可回滚备份：" + change.path());
        }
        AgentTask task = runtime.getTask(taskId);
        AgentTool rollbackTool = toolRegistry.find("builtin.filesystem.rollback_file")
                .orElseThrow(() -> new IllegalStateException("未注册回滚工具：builtin.filesystem.rollback_file"));
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("path", change.path());
        arguments.put("backupPath", change.backupPath());
        if (request.charset() != null && !request.charset().isBlank()) {
            arguments.put("charset", request.charset());
        }
        Instant startedAt = Instant.now();
        // 通过工具注册表调用回滚，复用 filesystem allowed-roots 和备份路径校验，不在 Controller 手写文件覆盖逻辑。
        ToolResult result = rollbackTool.execute(new ToolCall("builtin.filesystem.rollback_file", arguments), AgentContext.forTask(task));
        if (!result.success()) {
            throw new IllegalArgumentException("文件回滚失败：" + result.content());
        }
        String rollbackStepId = "manual-rollback-" + UUID.randomUUID();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("stepId", rollbackStepId);
        details.put("toolId", "builtin.filesystem.rollback_file");
        details.put("status", "COMPLETED");
        details.put("toolKind", "local");
        details.put("riskLevel", "high");
        details.put("inputPreview", preview(arguments.toString()));
        details.put("arguments", arguments.toString());
        details.put("outputPreview", preview(result.content(), 4000));
        details.put("output", result.content());
        details.put("outputLength", String.valueOf(result.content().length()));
        details.put("elapsedMs", String.valueOf(Duration.between(startedAt, Instant.now()).toMillis()));
        putIfNotBlank(details, "todoId", change.todoId());
        putIfNotBlank(details, "todoOrder", change.todoOrder());
        putIfNotBlank(details, "todoTitle", change.todoTitle());
        runtime.recordTaskEvent(taskId, "INFO", "tool.succeeded", "文件回滚完成", details);
        return fileReview(taskId, rollbackStepId, change.path(), change.backupPath());
    }

    public FileReviewView rollbackTaskFileSelection(
            String taskId,
            RollbackFileSelectionRequest request) throws IOException {
        FileChangeView change = findFileChange(taskId, nullToEmpty(request.stepId()), nullToEmpty(request.path()));
        if (change.backupPath() == null || change.backupPath().isBlank()) {
            throw new IllegalArgumentException("该文件变更没有可回滚备份：" + change.path());
        }
        Path target = Path.of(change.path()).toAbsolutePath().normalize();
        Path backup = Path.of(nullToEmpty(request.backupPath()).isBlank() ? change.backupPath() : request.backupPath())
                .toAbsolutePath()
                .normalize();
        if (!backup.toString().equals(change.backupPath())) {
            throw new IllegalArgumentException("backupPath 与任务变更记录不一致");
        }
        AgentTask task = runtime.getTask(taskId);
        AgentTool fileInfoTool = toolRegistry.find("builtin.filesystem.get_file_info")
                .orElseThrow(() -> new IllegalStateException("未注册文件校验工具：builtin.filesystem.get_file_info"));
        ToolResult fileInfo = fileInfoTool.execute(
                new ToolCall("builtin.filesystem.get_file_info", Map.of("path", change.path())),
                AgentContext.forTask(task));
        if (!fileInfo.success()) {
            throw new IllegalArgumentException("文件路径校验失败：" + fileInfo.content());
        }
        Charset charset = rollbackCharset(request.charset());
        String current = Files.exists(target) ? Files.readString(target, charset) : "";
        String before = Files.exists(backup) ? Files.readString(backup, charset) : "";
        int startLine = Math.max(1, request.startLine());
        int endLine = Math.max(startLine, request.endLine());
        String restored = "before".equalsIgnoreCase(nullToEmpty(request.base()))
                ? rollbackDeletedLineRange(current, before, startLine, endLine, request.insertAfterLine())
                : rollbackLineRange(current, before, startLine, endLine, request.selectedText());
        AgentTool writeTool = toolRegistry.find("builtin.filesystem.write_file")
                .orElseThrow(() -> new IllegalStateException("未注册文件写入工具：builtin.filesystem.write_file"));
        Map<String, String> writeArguments = new LinkedHashMap<>();
        writeArguments.put("path", change.path());
        writeArguments.put("content", restored);
        writeArguments.put("charset", charset.name());
        // 局部回滚最终仍走 filesystem 写入工具，复用 allowed-roots、blocked-patterns 和变更备份逻辑。
        ToolResult writeResult = writeTool.execute(new ToolCall("builtin.filesystem.write_file", writeArguments), AgentContext.forTask(task));
        if (!writeResult.success()) {
            throw new IllegalArgumentException("文件局部回滚写入失败：" + writeResult.content());
        }
        String rollbackStepId = "manual-selection-rollback-" + UUID.randomUUID();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("stepId", rollbackStepId);
        details.put("toolId", "builtin.filesystem.rollback_file");
        details.put("status", "COMPLETED");
        details.put("toolKind", "local");
        details.put("riskLevel", "high");
        details.put("inputPreview", "rollback selection " + startLine + "-" + endLine + " " + target);
        details.put("arguments", "path=" + target + ", backupPath=" + backup + ", base="
                + firstNonBlank(request.base(), "current") + ", startLine=" + startLine + ", endLine=" + endLine);
        details.put("output", partialRollbackOutput(target, backup, current, restored));
        details.put("outputPreview", preview(details.get("output"), 4000));
        details.put("outputLength", String.valueOf(details.get("output").length()));
        putIfNotBlank(details, "todoId", change.todoId());
        putIfNotBlank(details, "todoOrder", change.todoOrder());
        putIfNotBlank(details, "todoTitle", change.todoTitle());
        runtime.recordTaskEvent(taskId, "INFO", "tool.succeeded", "文件局部回滚完成", details);
        return fileReview(taskId, rollbackStepId, change.path(), change.backupPath());
    }

    private List<TaskAuditToolView> buildAuditTools(List<AgentEvent> events, List<AgentStep> steps) {
        Map<String, AgentStep> stepsById = steps.stream()
                .collect(Collectors.toMap(AgentStep::id, step -> step, (left, right) -> right, LinkedHashMap::new));
        Map<String, AgentEvent> latestToolEvents = new LinkedHashMap<>();
        events.stream()
                .filter(event -> event.type() != null && event.type().startsWith("tool."))
                .filter(event -> !nullToEmpty(event.details().get("stepId")).isBlank())
                .forEach(event -> latestToolEvents.put(event.details().get("stepId"), event));
        return latestToolEvents.values().stream()
                .map(event -> toAuditToolView(event, stepsById.get(event.details().get("stepId"))))
                .sorted(Comparator.comparing(TaskAuditToolView::startedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private TaskAuditToolView toAuditToolView(AgentEvent event, AgentStep step) {
        Map<String, String> details = event.details() == null ? Map.of() : event.details();
        String status = switch (nullToEmpty(event.type())) {
            case "tool.failed" -> "failed";
            case "tool.approval_requested" -> "waiting_approval";
            case "tool.approval_granted" -> "approved";
            case "tool.approval_rejected" -> "rejected";
            case "tool.succeeded" -> "completed";
            default -> "running";
        };
        // 审计接口只做事实归并：事件保存预览，step 保存最终状态，两者互补展示。
        return new TaskAuditToolView(
                nullToEmpty(details.get("stepId")),
                nullToEmpty(details.get("toolId")),
                status,
                nullToEmpty(details.get("riskLevel")),
                firstNonBlank(details.get("toolPermissionMode"), details.get("approvalMode")),
                nullToEmpty(details.get("todoTitle")),
                firstNonBlank(details.get("inputPreview"), step == null ? "" : step.input().toString()),
                firstNonBlank(details.get("outputPreview"), step == null ? "" : preview(step.output(), 1200)),
                firstNonBlank(details.get("error"), step == null ? "" : step.error()),
                parseNullableLong(details.get("elapsedMs")),
                firstNonNull(parseEpochMillis(details.get("startedAt")), step == null ? null : step.startedAt()),
                step == null ? event.createdAt() : step.finishedAt());
    }

    private List<TaskAuditApprovalView> buildAuditApprovals(List<AgentEvent> events) {
        Map<String, TaskAuditApprovalView> approvals = new LinkedHashMap<>();
        for (AgentEvent event : events) {
            Map<String, String> details = event.details() == null ? Map.of() : event.details();
            String stepId = nullToEmpty(details.get("stepId"));
            if (stepId.isBlank()) {
                continue;
            }
            if ("tool.approval_requested".equals(event.type())) {
                approvals.put(stepId, new TaskAuditApprovalView(
                        stepId,
                        nullToEmpty(details.get("toolId")),
                        nullToEmpty(details.get("approvalKey")),
                        "requested",
                        firstNonBlank(details.get("error"), event.message()),
                        event.createdAt(),
                        null));
            }
            if ("tool.approval_granted".equals(event.type())) {
                TaskAuditApprovalView requested = approvals.get(stepId);
                approvals.put(stepId, new TaskAuditApprovalView(
                        stepId,
                        firstNonBlank(details.get("toolId"), requested == null ? "" : requested.toolId()),
                        firstNonBlank(details.get("approvalKey"), requested == null ? "" : requested.approvalKey()),
                        "granted",
                        requested == null ? "" : requested.reason(),
                        requested == null ? null : requested.requestedAt(),
                        event.createdAt()));
            }
            if ("tool.approval_rejected".equals(event.type())) {
                TaskAuditApprovalView requested = approvals.get(stepId);
                approvals.put(stepId, new TaskAuditApprovalView(
                        stepId,
                        firstNonBlank(details.get("toolId"), requested == null ? "" : requested.toolId()),
                        firstNonBlank(details.get("approvalKey"), requested == null ? "" : requested.approvalKey()),
                        "rejected",
                        firstNonBlank(details.get("reason"), requested == null ? "" : requested.reason()),
                        requested == null ? null : requested.requestedAt(),
                        event.createdAt()));
            }
        }
        return approvals.values().stream()
                .sorted(Comparator.comparing(TaskAuditApprovalView::requestedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private TaskAuditResumeView buildAuditResume(List<AgentEvent> events) {
        AgentEvent requested = null;
        AgentEvent checkpoint = null;
        for (AgentEvent event : events) {
            if ("task.resume_requested".equals(event.type())) {
                requested = event;
            } else if ("task.resume_checkpoint".equals(event.type())) {
                checkpoint = event;
            }
        }
        if (requested == null && checkpoint == null) {
            return new TaskAuditResumeView(false, "", "", "", "", "", "", "", "", "", null, null);
        }
        Map<String, String> requestedDetails = requested == null || requested.details() == null ? Map.of() : requested.details();
        Map<String, String> checkpointDetails = checkpoint == null || checkpoint.details() == null ? Map.of() : checkpoint.details();
        // checkpoint 事件包含运行时最终恢复点；请求事件补充来源状态，二者合并成任务审计页的单一事实视图。
        return new TaskAuditResumeView(
                true,
                firstNonBlank(checkpointDetails.get("resumeFromTaskId"), requestedDetails.get("resumeFromTaskId")),
                nullToEmpty(requestedDetails.get("resumeFromStatus")),
                firstNonBlank(checkpointDetails.get("todoId"), requestedDetails.get("todoId")),
                firstNonBlank(checkpointDetails.get("todoOrder"), requestedDetails.get("todoOrder")),
                firstNonBlank(checkpointDetails.get("todoTitle"), requestedDetails.get("todoTitle")),
                firstNonBlank(checkpointDetails.get("todoStatus"), requestedDetails.get("todoStatus")),
                firstNonBlank(checkpointDetails.get("resumeMode"), requestedDetails.get("resumeMode")),
                firstNonBlank(checkpointDetails.get("resumeInstruction"), requestedDetails.get("resumeInstruction")),
                nullToEmpty(checkpointDetails.get("checkpoint")),
                requested == null ? null : requested.createdAt(),
                checkpoint == null ? null : checkpoint.createdAt());
    }

    private List<TaskAuditTimelineView> buildAuditTimeline(List<AgentEvent> events) {
        return events.stream()
                .map(event -> {
                    Map<String, String> details = event.details() == null ? Map.of() : event.details();
                    return new TaskAuditTimelineView(
                            event.id(),
                            nullToEmpty(event.type()),
                            nullToEmpty(event.level()),
                            nullToEmpty(event.message()),
                            nullToEmpty(details.get("toolId")),
                            nullToEmpty(details.get("stepId")),
                            nullToEmpty(details.get("todoTitle")),
                            event.createdAt());
                })
                .sorted(Comparator.comparing(TaskAuditTimelineView::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    private Instant parseEpochMillis(String value) {
        Long millis = parseNullableLong(value);
        if (millis == null || millis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(millis);
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private CommandRunView toCommandRunView(AgentEvent event) {
        Map<String, String> details = event.details() == null ? Map.of() : event.details();
        String toolId = nullToEmpty(details.get("toolId"));
        if (!toolId.equals("builtin.execute.command")
                && !toolId.equals("builtin.process.start")
                && !toolId.equals("builtin.process.stop")
                && !toolId.equals("builtin.process.status")
                && !toolId.equals("builtin.process.logs")) {
            return null;
        }
        String output = firstNonBlank(details.get("output"), details.get("outputPreview"));
        String command = firstNonBlank(parseChangeLine(output, "command"), details.get("inputPreview"));
        // 开发总结直接从事件事实生成，避免再次调用模型导致摘要和真实工具输出不一致。
        return new CommandRunView(
                nullToEmpty(details.get("stepId")),
                toolId,
                "tool.failed".equals(event.type()) ? "failed" : "completed",
                command,
                parseChangeLine(output, "cwd"),
                parseNullableInt(parseChangeLine(output, "exitCode")),
                nullToEmpty(details.get("riskLevel")),
                parseNullableLong(firstNonBlank(parseChangeLine(output, "elapsedMs"), details.get("elapsedMs"))),
                firstNonBlank(details.get("outputPreview"), preview(output, 1200)));
    }

    private List<String> collectDevelopmentFailures(List<AgentEvent> events, List<CommandRunView> commands) {
        List<String> failures = new ArrayList<>();
        events.stream()
                .filter(event -> "task.failed".equals(event.type()) || "tool.failed".equals(event.type()))
                .map(event -> firstNonBlank(event.message(), event.details() == null ? "" : event.details().get("error")))
                .filter(item -> item != null && !item.isBlank())
                .forEach(item -> addUnique(failures, preview(item, 300)));
        commands.stream()
                .filter(command -> command.exitCode() != null && command.exitCode() != 0)
                .map(command -> command.command() + " exitCode=" + command.exitCode())
                .forEach(item -> addUnique(failures, preview(item, 300)));
        return failures;
    }

    private List<String> collectDevelopmentRisks(List<CommandRunView> commands) {
        List<String> risks = new ArrayList<>();
        commands.stream()
                .filter(command -> "high".equalsIgnoreCase(command.riskLevel()) || "medium".equalsIgnoreCase(command.riskLevel()))
                .map(command -> command.riskLevel() + " risk: " + command.command())
                .forEach(item -> addUnique(risks, preview(item, 260)));
        return risks;
    }

    private List<FailureAnalysisView> collectFailureAnalyses(List<AgentEvent> events, List<CommandRunView> commands) {
        List<FailureAnalysisView> analyses = new ArrayList<>();
        commands.stream()
                .filter(command -> "failed".equals(command.status()) || (command.exitCode() != null && command.exitCode() != 0))
                .map(command -> analyzeCommandFailure(command, command.outputPreview()))
                .forEach(analysis -> addFailureAnalysis(analyses, analysis));
        events.stream()
                .filter(event -> "task.failed".equals(event.type()) || "tool.failed".equals(event.type()))
                .forEach(event -> {
                    String evidence = firstNonBlank(event.message(), event.details() == null ? "" : event.details().get("error"));
                    if (evidence != null && !evidence.isBlank()) {
                        addFailureAnalysis(analyses, analyzeGenericFailure(evidence));
                    }
                });
        return analyses;
    }

    private FailureAnalysisView analyzeCommandFailure(CommandRunView command, String evidence) {
        String category = classifyFailure(nullToEmpty(command.command()) + "\n" + nullToEmpty(evidence));
        return new FailureAnalysisView(
                category,
                failureCategoryText(category),
                command.command(),
                command.cwd(),
                isRetryableFailure(category),
                retryLimitForFailure(category),
                nextActionForFailure(category),
                preview(firstNonBlank(evidence, command.command()), 500));
    }

    private FailureAnalysisView analyzeGenericFailure(String evidence) {
        String category = classifyFailure(evidence);
        return new FailureAnalysisView(
                category,
                failureCategoryText(category),
                "",
                "",
                isRetryableFailure(category),
                retryLimitForFailure(category),
                nextActionForFailure(category),
                preview(evidence, 500));
    }

    private void addFailureAnalysis(List<FailureAnalysisView> analyses, FailureAnalysisView analysis) {
        String key = analysis.category() + "|" + nullToEmpty(analysis.command()) + "|" + nullToEmpty(analysis.cwd());
        boolean exists = analyses.stream()
                .anyMatch(item -> (item.category() + "|" + nullToEmpty(item.command()) + "|" + nullToEmpty(item.cwd())).equals(key));
        if (!exists) {
            analyses.add(analysis);
        }
    }

    private String classifyFailure(String text) {
        String normalized = nullToEmpty(text).toLowerCase(Locale.ROOT);
        if (normalized.contains("requiresprojectconfirmation: true")
                || normalized.contains("project-directory")
                || normalized.contains("不是可运行项目目录")) {
            return "project-directory";
        }
        if (normalized.contains("compilation failure") || normalized.contains("编译失败")
                || normalized.contains("cannot find symbol") || normalized.contains("找不到符号")) {
            return "compile";
        }
        if (normalized.contains("test failures") || normalized.contains("there are test failures")
                || normalized.contains("assertion") || normalized.contains("测试失败")) {
            return "test";
        }
        if (normalized.contains("address already in use") || normalized.contains("端口")
                || normalized.contains("port") && normalized.contains("in use")) {
            return "startup-port";
        }
        if (normalized.contains("access denied") || normalized.contains("permission denied")
                || normalized.contains("权限") || normalized.contains("未审批")) {
            return "permission";
        }
        if (normalized.contains("status=401") || normalized.contains("status: 401")
                || normalized.contains("status=403") || normalized.contains("status: 403")
                || normalized.contains("登录") || normalized.contains("authentication")) {
            return "auth";
        }
        if (normalized.contains("not found") || normalized.contains("不存在")
                || normalized.contains("no such file") || normalized.contains("找不到")) {
            return "not-found";
        }
        if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("超时")) {
            return "timeout";
        }
        if (normalized.contains("connection reset") || normalized.contains("unknownhost")
                || normalized.contains("dns") || normalized.contains("ssl")) {
            return "network";
        }
        if (normalized.contains("could not resolve dependencies") || normalized.contains("依赖")
                || normalized.contains("npm err") || normalized.contains("maven") && normalized.contains("dependency")) {
            return "dependency";
        }
        return "unknown";
    }

    private boolean isRetryableFailure(String category) {
        return switch (category) {
            case "timeout", "network", "unknown" -> true;
            default -> false;
        };
    }

    private int retryLimitForFailure(String category) {
        return isRetryableFailure(category) ? 2 : 1;
    }

    private String failureCategoryText(String category) {
        return switch (category) {
            case "project-directory" -> "项目目录不明确";
            case "compile" -> "编译失败";
            case "test" -> "测试失败";
            case "startup-port" -> "启动端口冲突";
            case "permission" -> "权限或审批不足";
            case "auth" -> "认证失败";
            case "not-found" -> "路径或资源不存在";
            case "timeout" -> "执行超时";
            case "network" -> "网络异常";
            case "dependency" -> "依赖解析失败";
            default -> "未知失败";
        };
    }

    private String nextActionForFailure(String category) {
        return switch (category) {
            case "project-directory" -> "先确认正确项目目录，再继续执行原任务。";
            case "compile" -> "读取编译错误定位源码，修复后重新运行验证命令。";
            case "test" -> "读取失败测试和断言信息，修复业务逻辑或测试预期后重跑。";
            case "startup-port" -> "查看占用端口的进程，换端口或停止旧进程后再启动。";
            case "permission" -> "请求用户审批或调整 allowed roots/权限策略后再执行。";
            case "auth" -> "让用户确认凭据、登录状态或 API Key，不要重复无凭据请求。";
            case "not-found" -> "重新搜索项目结构或确认路径，不要重复访问同一路径。";
            case "timeout" -> "允许最多 2 次重试；仍失败则拆分命令、增大超时或查看日志。";
            case "network" -> "允许最多 2 次重试；仍失败则检查网络、代理或依赖源。";
            case "dependency" -> "检查依赖源、版本和锁文件，必要时请求用户批准安装或修复依赖。";
            default -> "先读取完整错误输出，换参数或换方案后再重试。";
        };
    }

    private List<String> suggestTestCommands(AgentTask task, List<CommandRunView> commands) {
        return buildVerificationPlan(task, commands).stream()
                .map(VerificationCommandView::command)
                .toList();
    }

    private List<VerificationCommandView> buildVerificationPlan(AgentTask task, List<CommandRunView> commands) {
        List<VerificationCommandView> plan = new ArrayList<>();
        List<Path> candidatePaths = collectProjectCandidatePaths(task, commands);
        addProjectVerificationCommands(candidatePaths, commands, plan);
        // 全局验证命令在项目专属命令之后展示，避免 monorepo 子模块命令被通用命令盖掉。
        properties.getLocal().getTestCommands().forEach(command ->
                addVerificationCommand(plan, commands, command, primaryCwd(candidatePaths), "global-config", "本地配置页的全局验证命令"));
        candidatePaths.forEach(path -> detectVerificationCommands(path, commands, plan));
        return plan;
    }

    private List<Path> collectProjectCandidatePaths(AgentTask task, List<CommandRunView> commands) {
        List<Path> candidates = new ArrayList<>();
        for (String key : List.of("activeProjectPath", "projectPath", "workspace.projectPath", "cwd")) {
            addCandidatePath(candidates, task.metadata().get(key));
        }
        commands.stream()
                .map(CommandRunView::cwd)
                .forEach(cwd -> addCandidatePath(candidates, cwd));
        return candidates;
    }

    private void addCandidatePath(List<Path> candidates, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            if (!candidates.contains(path)) {
                candidates.add(path);
            }
        } catch (Exception ignored) {
            // 模型或旧事件可能写入非本机路径；无法解析时跳过，不影响其他建议来源。
        }
    }

    private void addProjectVerificationCommands(List<Path> candidatePaths, List<CommandRunView> commands, List<VerificationCommandView> plan) {
        Map<Path, List<String>> configured = new LinkedHashMap<>();
        properties.getLocal().getProjectTestCommands().forEach((rawPath, configuredCommands) -> {
            try {
                Path path = Path.of(rawPath).toAbsolutePath().normalize();
                List<String> normalizedCommands = normalizeConfigList(configuredCommands);
                if (!normalizedCommands.isEmpty()) {
                    configured.put(path, normalizedCommands);
                }
            } catch (Exception ignored) {
                // 配置里的历史路径可能来自其他机器；跳过无效路径，避免摘要接口失败。
            }
        });
        configured.entrySet().stream()
                .filter(entry -> candidatePaths.stream().anyMatch(candidate -> candidate.equals(entry.getKey()) || candidate.startsWith(entry.getKey())))
                .sorted((left, right) -> Integer.compare(right.getKey().getNameCount(), left.getKey().getNameCount()))
                .forEach(entry -> entry.getValue().forEach(command ->
                        addVerificationCommand(plan, commands, command, entry.getKey(), "project-config", "按项目目录匹配的验证命令")));
    }

    private void detectTestCommands(Path rawPath, List<String> suggestions) {
        Path path = rawPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(path.resolve("pom.xml"))) {
            addUnique(suggestions, "mvn test");
        }
        if (Files.isRegularFile(path.resolve("package.json"))) {
            addUnique(suggestions, "npm test");
        }
        if (Files.isRegularFile(path.resolve("pytest.ini"))
                || Files.isRegularFile(path.resolve("pyproject.toml"))
                || Files.isDirectory(path.resolve("tests"))) {
            addUnique(suggestions, "pytest");
        }
    }

    private void detectVerificationCommands(Path rawPath, List<CommandRunView> commands, List<VerificationCommandView> plan) {
        Path path = rawPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(path.resolve("pom.xml"))) {
            addVerificationCommand(plan, commands, "mvn test", path, "auto-detect", "检测到 pom.xml");
        }
        if (Files.isRegularFile(path.resolve("package.json"))) {
            addVerificationCommand(plan, commands, "npm test", path, "auto-detect", "检测到 package.json");
        }
        if (Files.isRegularFile(path.resolve("pytest.ini"))
                || Files.isRegularFile(path.resolve("pyproject.toml"))
                || Files.isDirectory(path.resolve("tests"))) {
            addVerificationCommand(plan, commands, "pytest", path, "auto-detect", "检测到 Python 测试配置或 tests 目录");
        }
    }

    private Path primaryCwd(List<Path> candidatePaths) {
        return candidatePaths.isEmpty() ? null : candidatePaths.get(0);
    }

    private void addVerificationCommand(List<VerificationCommandView> plan, List<CommandRunView> commands,
                                        String command, Path cwd, String source, String reason) {
        String normalizedCommand = command == null ? "" : command.trim();
        if (normalizedCommand.isBlank()) {
            return;
        }
        String cwdText = cwd == null ? "" : cwd.toString();
        String key = normalizedCommand.toLowerCase(Locale.ROOT) + "\n" + cwdText.toLowerCase(Locale.ROOT);
        boolean exists = plan.stream()
                .anyMatch(item -> (item.command().toLowerCase(Locale.ROOT) + "\n" + nullToEmpty(item.cwd()).toLowerCase(Locale.ROOT)).equals(key));
        if (exists) {
            return;
        }
        CommandRunView lastRun = lastMatchingCommand(commands, normalizedCommand, cwdText);
        plan.add(new VerificationCommandView(
                normalizedCommand,
                cwdText,
                source,
                reason,
                lastRun != null,
                lastRun == null ? "pending" : lastRun.status(),
                lastRun == null ? null : lastRun.exitCode(),
                lastRun == null ? null : lastRun.elapsedMs()));
    }

    private CommandRunView lastMatchingCommand(List<CommandRunView> commands, String command, String cwd) {
        String normalizedCommand = normalizeCommandText(command);
        String normalizedCwd = nullToEmpty(cwd).toLowerCase(Locale.ROOT);
        CommandRunView matched = null;
        for (CommandRunView run : commands) {
            if (!normalizeCommandText(run.command()).contains(normalizedCommand)) {
                continue;
            }
            if (!normalizedCwd.isBlank() && !nullToEmpty(run.cwd()).toLowerCase(Locale.ROOT).equals(normalizedCwd)) {
                continue;
            }
            matched = run;
        }
        return matched;
    }

    private String normalizeCommandText(String command) {
        return nullToEmpty(command).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private boolean isTestCommand(String command) {
        String normalized = nullToEmpty(command).toLowerCase();
        return normalized.contains("mvn test")
                || normalized.contains("mvnw test")
                || normalized.contains("npm test")
                || normalized.contains("npm run test")
                || normalized.contains("pnpm test")
                || normalized.contains("yarn test")
                || normalized.contains("pytest")
                || normalized.contains("gradle test")
                || normalized.contains("gradlew test");
    }

    private FinalResultView buildFinalResult(AgentTask task,
                                             List<FileChangeView> changes,
                                             List<CommandRunView> commands,
                                             List<CommandRunView> tests,
                                             List<String> failures,
                                             List<String> risks,
                                             List<VerificationCommandView> verificationPlan,
                                             List<FailureAnalysisView> failureAnalyses) {
        long failedCommands = commands.stream()
                .filter(command -> "failed".equals(command.status()) || (command.exitCode() != null && command.exitCode() != 0))
                .count();
        boolean hasFailedVerification = verificationPlan.stream()
                .anyMatch(item -> item.alreadyRun() && ("failed".equals(item.lastStatus()) || (item.lastExitCode() != null && item.lastExitCode() != 0)));
        boolean hasPendingVerification = verificationPlan.stream().anyMatch(item -> !item.alreadyRun());
        String verificationStatus = hasFailedVerification
                ? "failed"
                : hasPendingVerification ? "pending" : (verificationPlan.isEmpty() && tests.isEmpty() ? "missing" : "passed");
        boolean hasBlockingFailure = failedCommands > 0 || !failures.isEmpty() || hasFailedVerification;
        boolean readyForCommit = !changes.isEmpty() && !hasBlockingFailure && !"pending".equals(verificationStatus) && !"missing".equals(verificationStatus);
        String outcome = finalOutcome(task.status().name(), hasBlockingFailure, verificationStatus);
        List<String> remainingRisks = new ArrayList<>();
        risks.forEach(risk -> addUnique(remainingRisks, preview(risk, 220)));
        failureAnalyses.stream()
                .map(FailureAnalysisView::nextAction)
                .forEach(action -> addUnique(remainingRisks, preview(action, 220)));
        List<String> nextActions = finalNextActions(changes, verificationStatus, hasBlockingFailure, readyForCommit);
        return new FinalResultView(
                outcome,
                finalResultSummary(changes.size(), commands.size(), tests.size(), verificationStatus, hasBlockingFailure),
                verificationStatus,
                readyForCommit,
                changes.size(),
                commands.size(),
                tests.size(),
                Math.toIntExact(failedCommands),
                risks.size(),
                remainingRisks,
                nextActions);
    }

    private String finalOutcome(String taskStatus, boolean hasBlockingFailure, String verificationStatus) {
        if (hasBlockingFailure || "failed".equals(verificationStatus)) {
            return "failed";
        }
        if ("pending".equals(verificationStatus) || "missing".equals(verificationStatus)) {
            return "needs-verification";
        }
        return "COMPLETED".equalsIgnoreCase(taskStatus) || "SUCCEEDED".equalsIgnoreCase(taskStatus)
                ? "completed"
                : "in-progress";
    }

    private String finalResultSummary(int changedFiles, int commandsRun, int testsRun,
                                      String verificationStatus, boolean hasBlockingFailure) {
        if (hasBlockingFailure) {
            return "任务存在失败或风险，需要先处理失败项再继续。";
        }
        if ("pending".equals(verificationStatus)) {
            return "任务已有变更，但仍有验证命令未执行。";
        }
        if ("missing".equals(verificationStatus)) {
            return "任务已有执行记录，但未检测到可用验证命令。";
        }
        return "任务已形成可审查结果：文件变更 " + changedFiles + " 个，命令 " + commandsRun + " 条，测试/验证 " + testsRun + " 条。";
    }

    private List<String> finalNextActions(List<FileChangeView> changes, String verificationStatus,
                                          boolean hasBlockingFailure, boolean readyForCommit) {
        List<String> actions = new ArrayList<>();
        if (hasBlockingFailure) {
            actions.add("先处理失败分析中的下一步建议，再继续任务。");
        }
        if ("pending".equals(verificationStatus) || "missing".equals(verificationStatus)) {
            actions.add("运行验证计划中的测试/编译命令，确认变更可用。");
        }
        if (!changes.isEmpty()) {
            actions.add("审查文件 diff，确认变更符合预期。");
        }
        if (readyForCommit) {
            actions.add("确认 Git diff 后可使用下方 commit message 草稿。");
        }
        if (actions.isEmpty()) {
            actions.add("继续执行任务或补充更明确的开发目标。");
        }
        return actions;
    }

    private GitReviewView buildGitReview(AgentTask task, List<CommandRunView> commands) {
        List<Path> candidatePaths = collectProjectCandidatePaths(task, commands);
        String cwd = primaryCwd(candidatePaths) == null ? "" : primaryCwd(candidatePaths).toString();
        CommandRunView statusRun = lastGitCommand(commands, "status");
        CommandRunView diffRun = lastGitCommand(commands, "diff");
        if (cwd.isBlank()) {
            cwd = firstNonBlank(statusRun == null ? "" : statusRun.cwd(), diffRun == null ? "" : diffRun.cwd());
        }
        String statusCommand = statusRun == null ? "git status --short" : statusRun.command();
        String diffCommand = diffRun == null ? "git diff --stat && git diff" : diffRun.command();
        String nextAction = gitNextAction(statusRun, diffRun);
        return new GitReviewView(
                cwd,
                statusCommand,
                diffCommand,
                statusRun != null,
                diffRun != null,
                statusRun == null ? null : statusRun.exitCode(),
                diffRun == null ? null : diffRun.exitCode(),
                statusRun == null ? "" : statusRun.outputPreview(),
                diffRun == null ? "" : diffRun.outputPreview(),
                nextAction);
    }

    private CommandRunView lastGitCommand(List<CommandRunView> commands, String gitSubCommand) {
        CommandRunView matched = null;
        for (CommandRunView command : commands) {
            String normalized = normalizeCommandText(command.command());
            if (normalized.contains("git " + gitSubCommand)) {
                matched = command;
            }
        }
        return matched;
    }

    private String gitNextAction(CommandRunView statusRun, CommandRunView diffRun) {
        if (statusRun == null && diffRun == null) {
            return "尚未记录 Git 审查命令，建议先执行 git status --short 和 git diff。";
        }
        if (statusRun != null && statusRun.exitCode() != null && statusRun.exitCode() != 0) {
            return "git status 执行失败，先确认 cwd 是否是 Git 仓库。";
        }
        if (diffRun == null) {
            return "已记录 git status，建议继续执行 git diff 查看具体改动。";
        }
        if (diffRun.exitCode() != null && diffRun.exitCode() != 0) {
            return "git diff 执行失败，先确认仓库状态和命令参数。";
        }
        return "Git 审查命令已执行，可结合文件审查和 commit message 草稿确认提交内容。";
    }

    private String buildCommitMessage(AgentTask task, List<FileChangeView> changes, List<CommandRunView> tests, List<String> failures) {
        String scope = changes.isEmpty()
                ? "agent"
                : basename(changes.get(0).path()).replaceAll("[^A-Za-z0-9._-]", "");
        String summary = task.input() == null || task.input().isBlank() ? "update local agent workflow" : preview(task.input(), 68);
        StringBuilder builder = new StringBuilder("feat(").append(scope.isBlank() ? "agent" : scope).append("): ").append(summary);
        if (!tests.isEmpty()) {
            builder.append("\n\nTests:\n");
            tests.forEach(test -> builder.append("- ").append(test.command()).append(" -> exitCode=").append(test.exitCode() == null ? "-" : test.exitCode()).append('\n'));
        }
        if (!failures.isEmpty()) {
            builder.append("\nRisks:\n");
            failures.forEach(failure -> builder.append("- ").append(failure).append('\n'));
        }
        return builder.toString().trim();
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private void putIfNotBlank(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private Integer parseNullableInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseNullableLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<FileChangeView> fileChangesForTask(String taskId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        int eventScanLimit = Math.min(FILE_CHANGE_EVENT_SCAN_LIMIT, Math.max(safeLimit * 10, 1000));
        List<FileChangeView> rawChanges = runtime.getTaskEvents(taskId, eventScanLimit).stream()
                .filter(event -> "tool.succeeded".equals(event.type()) || "tool.failed".equals(event.type()))
                .map(event -> toFileChangeView(taskId, event))
                .filter(change -> change != null)
                .filter(change -> !isIgnoredWorkspacePath(change.path()))
                .toList();
        // limit 是最终文件审查列表的分页大小，不应截断 task event 扫描窗口，否则长任务后段的普通工具调用会挤掉早期文件变更。
        return FileChangeReviewSupport.latestFileChanges(rawChanges).stream()
                .limit(safeLimit)
                .toList();
    }

    private boolean isIgnoredWorkspacePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return false;
        }
        for (String pattern : properties.getLocal().getIgnorePatterns()) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim());
            if (matcher.matches(path) || matcher.matches(path.getFileName())) {
                return true;
            }
            Path workspaceRoot = Path.of(properties.getLocal().getWorkspaceRoot()).toAbsolutePath().normalize();
            if (path.startsWith(workspaceRoot) && matcher.matches(workspaceRoot.relativize(path))) {
                return true;
            }
        }
        return false;
    }

    private FileChangeView findFileChange(String taskId, String stepId, String path) {
        return fileChangesForTask(taskId, 1000).stream()
                .filter(item -> item.stepId().equals(stepId) && item.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("任务中不存在该文件变更：" + path));
    }

    private String basename(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Path fileName = Path.of(path).getFileName();
        return fileName == null ? path : fileName.toString();
    }

    private void startDetached(List<String> command) throws IOException {
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private String readReviewText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        IOException lastError = null;
        for (Charset charset : reviewCharsets()) {
            try {
                // 文件审查不能假设用户项目都是 UTF-8；严格解码失败后按本机/Windows 中文编码回退。
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                lastError = new IOException("文件编码无法解码：" + path + " charset=" + charset.name(), e);
            }
        }
        throw lastError == null ? new IOException("文件读取失败：" + path) : lastError;
    }

    private List<Charset> reviewCharsets() {
        List<Charset> charsets = new ArrayList<>();
        charsets.add(StandardCharsets.UTF_8);
        addCharset(charsets, Charset.defaultCharset());
        addCharset(charsets, "GB18030");
        addCharset(charsets, "GBK");
        addCharset(charsets, "MS936");
        return charsets;
    }

    private void addCharset(List<Charset> charsets, String name) {
        if (Charset.isSupported(name)) {
            addCharset(charsets, Charset.forName(name));
        }
    }

    private void addCharset(List<Charset> charsets, Charset charset) {
        if (charsets.stream().noneMatch(item -> item.name().equalsIgnoreCase(charset.name()))) {
            charsets.add(charset);
        }
    }

    private Charset rollbackCharset(String value) {
        return value == null || value.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(value.trim());
    }

    private String rollbackLineRange(String current, String backup, int startLine, int endLine, String selectedText) {
        String newline = current.contains("\r\n") ? "\r\n" : "\n";
        List<String> currentLines = new ArrayList<>(List.of(current.split("\\R", -1)));
        List<String> backupLines = new ArrayList<>(List.of(backup.split("\\R", -1)));
        int startIndex = startLine - 1;
        int endExclusive = Math.min(endLine, currentLines.size());
        if (startIndex < 0 || startIndex >= currentLines.size() || startIndex >= endExclusive) {
            throw new IllegalArgumentException("选中行范围不在当前文件内：" + startLine + "-" + endLine);
        }
        String currentSelection = String.join(newline, currentLines.subList(startIndex, endExclusive));
        if (selectedText != null && !selectedText.isBlank() && !normalizeLineEndings(currentSelection).equals(normalizeLineEndings(selectedText))) {
            throw new IllegalArgumentException("当前文件内容已变化，选中内容校验失败，请刷新文件审查后重试");
        }
        List<String> replacement = startIndex >= backupLines.size()
                ? List.of()
                : new ArrayList<>(backupLines.subList(startIndex, Math.min(endLine, backupLines.size())));
        currentLines.subList(startIndex, endExclusive).clear();
        currentLines.addAll(startIndex, replacement);
        return String.join(newline, currentLines);
    }

    private String rollbackDeletedLineRange(String current, String backup, int startLine, int endLine, Integer insertAfterLine) {
        String newline = current.contains("\r\n") ? "\r\n" : backup.contains("\r\n") ? "\r\n" : "\n";
        List<String> currentLines = new ArrayList<>(List.of(current.split("\\R", -1)));
        List<String> backupLines = new ArrayList<>(List.of(backup.split("\\R", -1)));
        int backupStartIndex = startLine - 1;
        int backupEndExclusive = Math.min(endLine, backupLines.size());
        if (backupStartIndex < 0 || backupStartIndex >= backupLines.size() || backupStartIndex >= backupEndExclusive) {
            throw new IllegalArgumentException("备份文件中不存在要恢复的删除行：" + startLine + "-" + endLine);
        }
        List<String> replacement = new ArrayList<>(backupLines.subList(backupStartIndex, backupEndExclusive));
        int insertIndex = Math.max(0, Math.min(insertAfterLine == null ? backupStartIndex : insertAfterLine, currentLines.size()));
        // 删除-only hunk 在当前文件没有对应选中行，只能用备份行段插回当前文件的相邻位置。
        currentLines.addAll(insertIndex, replacement);
        return String.join(newline, currentLines);
    }

    private String normalizeLineEndings(String value) {
        return nullToEmpty(value).replace("\r\n", "\n").replace('\r', '\n');
    }

    private String partialRollbackOutput(Path target, Path backup, String beforeRollback, String restored) {
        return "changeType: rollback\n"
                + "path: " + target + "\n"
                + "backupPath: " + backup + "\n"
                + "rollbackMode: selection\n"
                + "diff:\n" + simpleDiff(beforeRollback, restored);
    }

    private String simpleDiff(String before, String after) {
        String[] oldLines = before.split("\\R", -1);
        String[] newLines = after.split("\\R", -1);
        StringBuilder diff = new StringBuilder("--- before\n+++ after\n");
        int max = Math.max(oldLines.length, newLines.length);
        int emitted = 0;
        for (int i = 0; i < max && emitted < 120; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine != null && oldLine.equals(newLine)) {
                continue;
            }
            if (oldLine != null) {
                diff.append("-").append(oldLine).append('\n');
                emitted++;
            }
            if (newLine != null && emitted < 120) {
                diff.append("+").append(newLine).append('\n');
                emitted++;
            }
        }
        if (emitted == 0) {
            diff.append("[无内容变化]\n");
        }
        return diff.toString();
    }

    private FileChangeView toFileChangeView(String taskId, AgentEvent event) {
        Map<String, String> details = event.details() == null ? Map.of() : event.details();
        String toolId = nullToEmpty(details.get("toolId"));
        if (!"builtin.filesystem.write_file".equals(toolId)
                && !"builtin.filesystem.rollback_file".equals(toolId)) {
            return null;
        }
        String output = firstNonBlank(details.get("output"), details.get("outputPreview"), "");
        String rawArguments = firstNonBlank(details.get("arguments"), details.get("inputPreview"), "");
        if (output.isBlank() && rawArguments.isBlank()) {
            return null;
        }
        boolean failed = "tool.failed".equals(event.type());
        String changeType = failed ? "failed" : parseChangeLine(output, "changeType");
        String path = firstNonBlank(parseChangeLine(output, "path"), parseArgumentValue(rawArguments, "path"));
        if (changeType.isBlank() || path.isBlank()) {
            return null;
        }
        String backupPath = firstNonBlank(parseChangeLine(output, "backupPath"), parseArgumentValue(rawArguments, "backupPath"));
        String diff = parseDiffBlock(output);
        int addedLines = countDiffLines(diff, '+');
        int deletedLines = countDiffLines(diff, '-');
        String stepId = nullToEmpty(details.get("stepId"));
        return new FileChangeView(
                stepId + ":" + path,
                taskId,
                stepId,
                toolId,
                changeType,
                path,
                backupPath,
                diff,
                addedLines,
                deletedLines,
                nullToEmpty(details.get("todoId")),
                nullToEmpty(details.get("todoOrder")),
                nullToEmpty(details.get("todoTitle")),
                event.createdAt(),
                "",
                0);
    }

    private String parseArgumentValue(String arguments, String key) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?:^|[,{\\s])" + Pattern.quote(key) + "\\s*[:=]\\s*\"?([^,}\\n\"]+)").matcher(arguments);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String parseChangeLine(String output, String key) {
        String prefix = key + ":";
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String parseDiffBlock(String output) {
        int index = output.indexOf("diff:");
        if (index < 0) {
            return "";
        }
        return output.substring(index + "diff:".length()).trim();
    }

    private int countDiffLines(String diff, char marker) {
        int count = 0;
        for (String line : diff.split("\\R")) {
            if (line.length() > 1 && line.charAt(0) == marker
                    && !line.startsWith("+++") && !line.startsWith("---")) {
                count++;
            }
        }
        return count;
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
                properties.getModel().getVisionModel(),
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
                toMemoryGovernanceConfigView(properties.getMemory().getGovernance()),
                toCostConfigView(properties.getCost()),
                toLocalDevelopmentConfigView(properties.getLocal()),
                toPolicySnapshotView(properties.getLocal()),
                toAuthConfigView(authProperties),
                modelViews
        );
    }

    private AuthConfigView toAuthConfigView(ServerAuthProperties authProperties) {
        return new AuthConfigView(
                authProperties.isRequired(),
                authProperties.isApiTokenRequired(),
                List.copyOf(authProperties.getProtectedPathPatterns()),
                List.copyOf(authProperties.getExcludedPathPatterns()),
                localUserService.isInitialized(),
                localUserService.count(),
                localUserService.ownerExists(),
                localUserService.supportedRoles(),
                toAuthRolePolicyViews(authProperties));
    }

    private Map<String, AuthRolePolicyView> toAuthRolePolicyViews(ServerAuthProperties authProperties) {
        Map<String, AuthRolePolicyView> result = new LinkedHashMap<>();
        authProperties.getRolePolicies().forEach((role, policy) -> {
            if (role == null || role.isBlank() || policy == null) {
                return;
            }
            // 快照只做脱敏后的结构化展示，真正执行仍由 TaskPolicyEnrichmentService 重新解析配置。
            result.put(role.trim(), new AuthRolePolicyView(
                    policy.isEnabled(),
                    nullToEmpty(policy.getPermissionMode()),
                    nullToEmpty(policy.getApprovalMode()),
                    List.copyOf(policy.getApprovedToolIds() == null ? List.of() : policy.getApprovedToolIds())));
        });
        return result;
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
        if (update.visionModel() != null) {
            properties.getModel().setVisionModel(update.visionModel().trim());
        }
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
        if (update.vision() != null) {
            config.setVision(update.vision());
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
        ClawAgentProperties.Governance governance = properties.getMemory().getGovernance();
        if (update.memoryGovernanceStaleAfterDays() != null) {
            governance.setStaleAfterDays(update.memoryGovernanceStaleAfterDays());
        }
        if (update.memoryGovernanceVeryStaleAfterDays() != null) {
            governance.setVeryStaleAfterDays(update.memoryGovernanceVeryStaleAfterDays());
        }
        if (update.memoryGovernanceAutoArchiveEnabled() != null) {
            governance.setAutoArchiveEnabled(update.memoryGovernanceAutoArchiveEnabled());
        }
        if (update.memoryGovernanceArchiveAfterDays() != null) {
            governance.setArchiveAfterDays(update.memoryGovernanceArchiveAfterDays());
        }
        if (update.memoryGovernanceArchiveBelowQuality() != null) {
            governance.setArchiveBelowQuality(update.memoryGovernanceArchiveBelowQuality());
        }
        ClawAgentProperties.Cost cost = properties.getCost();
        if (update.costCurrency() != null && !update.costCurrency().isBlank()) {
            cost.setCurrency(update.costCurrency().trim().toUpperCase(Locale.ROOT));
        }
        if (update.costRules() != null) {
            cost.setRules(toModelPriceMap(update.costRules()));
        }
        ClawAgentProperties.Local local = properties.getLocal();
        local.setWorkspaceRoot(firstNonBlank(update.localWorkspaceRoot(), local.getWorkspaceRoot()));
        local.setDefaultShell(firstNonBlank(update.localDefaultShell(), local.getDefaultShell()));
        local.setPermissionMode(normalizePermissionMode(firstNonBlank(update.localPermissionMode(), local.getPermissionMode())));
        if (update.localApprovedToolIds() != null) {
            local.setApprovedToolIds(normalizeConfigList(update.localApprovedToolIds()));
        }
        if (update.localAllowedRoots() != null) {
            local.setAllowedRoots(normalizeConfigList(update.localAllowedRoots()));
        }
        if (update.localRecentProjects() != null) {
            local.setRecentProjects(normalizeConfigList(update.localRecentProjects()));
        }
        if (update.localTestCommands() != null) {
            local.setTestCommands(normalizeConfigList(update.localTestCommands()));
        }
        if (update.localProjectTestCommands() != null) {
            local.setProjectTestCommands(normalizeConfigMap(update.localProjectTestCommands()));
        }
        if (update.localIgnorePatterns() != null) {
            local.setIgnorePatterns(normalizeConfigList(update.localIgnorePatterns()));
        }
        if (update.localSensitivePathPatterns() != null) {
            local.setSensitivePathPatterns(normalizeConfigList(update.localSensitivePathPatterns()));
        }
        syncLocalToolkitEnv(local);
    }

    private void applyPolicyConfigUpdate(PolicyConfigUpdate update) {
        if (update == null) {
            return;
        }
        ClawAgentProperties.Local local = properties.getLocal();
        // 策略专用入口只修改权限相关字段，避免能力页保存时覆盖模型、记忆、成本等配置。
        local.setPermissionMode(normalizePermissionMode(firstNonBlank(update.permissionMode(), local.getPermissionMode())));
        if (update.approvedToolIds() != null) {
            local.setApprovedToolIds(normalizeConfigList(update.approvedToolIds()));
        }
        if (update.workspaceRoot() != null && !update.workspaceRoot().isBlank()) {
            local.setWorkspaceRoot(update.workspaceRoot().trim());
        }
        if (update.defaultShell() != null && !update.defaultShell().isBlank()) {
            local.setDefaultShell(update.defaultShell().trim());
        }
        if (update.allowedRoots() != null) {
            local.setAllowedRoots(normalizeConfigList(update.allowedRoots()));
        }
        if (update.sensitivePathPatterns() != null) {
            local.setSensitivePathPatterns(normalizeConfigList(update.sensitivePathPatterns()));
        }
        syncLocalToolkitEnv(local);
    }

    private Map<String, String> policyAuditSnapshot(ClawAgentProperties.Local local, String prefix) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put(prefix + "permissionMode", normalizePermissionMode(local.getPermissionMode()));
        details.put(prefix + "approvedToolCount", String.valueOf(normalizeConfigList(local.getApprovedToolIds()).size()));
        details.put(prefix + "allowedRootCount", String.valueOf(localAllowedRoots(local).size()));
        details.put(prefix + "sensitivePathPatternCount", String.valueOf(normalizeConfigList(local.getSensitivePathPatterns()).size()));
        details.put(prefix + "workspaceRoot", firstNonBlank(local.getWorkspaceRoot(), ".clawagent/workspace"));
        details.put(prefix + "defaultShell", firstNonBlank(local.getDefaultShell(), ""));
        return details;
    }

    private void recordPolicyConfigUpdated(Map<String, String> before, Map<String, String> after) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("scope", "local");
        details.put("source", "admin.config.policy");
        details.putAll(before);
        details.putAll(after);
        // 策略配置不是某个任务的步骤，但必须进入全局审计页，便于企业接入后追踪权限变更。
        eventStore.saveEvent(new AgentEvent(
                UUID.randomUUID().toString(),
                "",
                "",
                "INFO",
                "policy.config_updated",
                "审批和本地权限策略已更新",
                details));
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
                config.getTimeoutSeconds(),
                config.isVision()
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

    private MemoryGovernanceConfigView toMemoryGovernanceConfigView(ClawAgentProperties.Governance config) {
        return new MemoryGovernanceConfigView(
                config.getStaleAfterDays(),
                config.getVeryStaleAfterDays(),
                config.isAutoArchiveEnabled(),
                config.getArchiveAfterDays(),
                config.getArchiveBelowQuality()
        );
    }

    private CostConfigView toCostConfigView(ClawAgentProperties.Cost config) {
        Map<String, CostRuleView> rules = new LinkedHashMap<>();
        config.getRules().forEach((model, price) -> rules.put(model, new CostRuleView(
                price.getInputPerMillion(),
                price.getOutputPerMillion(),
                price.getCurrency()
        )));
        return new CostConfigView(
                firstNonBlank(config.getCurrency(), "USD"),
                rules
        );
    }

    private Map<String, ClawAgentProperties.ModelPrice> toModelPriceMap(Map<String, CostRuleView> rules) {
        Map<String, ClawAgentProperties.ModelPrice> result = new LinkedHashMap<>();
        if (rules == null) {
            return result;
        }
        rules.forEach((model, rule) -> {
            String key = model == null ? "" : model.trim();
            if (key.isBlank() || rule == null) {
                return;
            }
            ClawAgentProperties.ModelPrice price = new ClawAgentProperties.ModelPrice();
            // 成本规则只影响页面估算，写入前做非负归一，避免错误配置产生负成本。
            price.setInputPerMillion(Math.max(0, rule.inputPerMillion()));
            price.setOutputPerMillion(Math.max(0, rule.outputPerMillion()));
            price.setCurrency(firstNonBlank(rule.currency(), ""));
            result.put(key, price);
        });
        return result;
    }

    private LocalDevelopmentConfigView toLocalDevelopmentConfigView(ClawAgentProperties.Local config) {
        return new LocalDevelopmentConfigView(
                config.getWorkspaceRoot(),
                config.getDefaultShell(),
                normalizePermissionMode(config.getPermissionMode()),
                List.copyOf(config.getApprovedToolIds()),
                localAllowedRoots(config),
                List.copyOf(config.getRecentProjects()),
                List.copyOf(config.getTestCommands()),
                copyConfigMap(config.getProjectTestCommands()),
                List.copyOf(config.getIgnorePatterns()),
                List.copyOf(config.getSensitivePathPatterns())
        );
    }

    private PolicySnapshotView toPolicySnapshotView(ClawAgentProperties.Local local) {
        String mode = normalizePermissionMode(local.getPermissionMode());
        Map<String, String> policyMetadata = new LinkedHashMap<>();
        policyMetadata.put("toolPermissionMode", mode);
        policyMetadata.put("approvedToolIds", String.join(",", normalizeConfigList(local.getApprovedToolIds())));
        policyMetadata.put("policy.approval.source", "local.permission-mode/local.approved-tool-ids");
        policyMetadata.put("policy.approval.scope", "local");
        policyMetadata.put("policy.resolutionOrder", "local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement");
        ApprovalPolicyResolution approvalResolution = ApprovalPolicyResolution.fromTaskMetadata(policyMetadata);
        ApprovalPolicy approvalPolicy = approvalResolution.policy();
        PermissionPolicy permissionPolicy = new PermissionPolicy(
                localAllowedRoots(local),
                normalizeConfigList(local.getSensitivePathPatterns()),
                firstNonBlank(local.getWorkspaceRoot(), ".clawagent/workspace")
        );
        ApprovalPolicyView approval = new ApprovalPolicyView(
                approvalPolicy.mode(),
                List.copyOf(approvalPolicy.approvedToolIds()),
                approvalPolicy.allowsHighRiskAutomatically(),
                approvalPolicy.isFullAccess(),
                approvalRequiresMediumOrUnknownConfirmation(approvalPolicy),
                approvalResolution.source(),
                approvalResolution.scope(),
                approvalResolution.resolutionOrder(),
                approvalResolution.overrideReason(),
                approvalResolution.conflictNotes()
        );
        PermissionPolicyView permission = new PermissionPolicyView(
                permissionPolicy.allowedRoots(),
                permissionPolicy.sensitivePathPatterns(),
                permissionPolicy.defaultCwd(),
                "local.allowed-roots/local.workspace-root/local.sensitive-path-patterns"
        );
        List<String> rules = new ArrayList<>();
        rules.add("low 风险查询类工具默认允许执行。");
        rules.add("medium/unknown 风险不会在 auto 模式静默放行，会退回人工确认。");
        rules.add("high 风险工具按 ask/auto/full/custom 模式进入审批或放行。");
        rules.add("allowed roots 和敏感路径规则始终由 execute/filesystem 工具链路强制校验。");
        rules.add("只读子 Agent 会被 ToolExecutionGuard 阻止执行非 low 风险工具。");
        List<PolicyResolutionLayerView> resolutionOrder = List.of(
                new PolicyResolutionLayerView(10, "local", "local", "local.*", "active",
                        "本地配置是当前单用户默认策略来源，提供审批模式、工具白名单、allowed roots 和敏感路径。"),
                new PolicyResolutionLayerView(20, "channel", "channel", "ChannelDefinition.approvalMode/approvedToolIds", "partial",
                        "Channel 入站会写入审批模式和工具白名单，进入任务策略合并链路。"),
                new PolicyResolutionLayerView(30, "user", "user", "LocalUser.metadata", "partial",
                        "本地用户 metadata 可提供 permissionMode/approvedToolIds，当前先作为轻量用户维度策略。"),
                new PolicyResolutionLayerView(40, "api-token", "api-token", "ApiToken.permissionMode/approvedToolIds/scopes", "active",
                        "API Token 可声明调用方权限模式、工具白名单和接口 scope，程序接入会先经过该层收紧。"),
                new PolicyResolutionLayerView(50, "device", "device", "DeviceRegistry.permissionMode/approvedToolIds", "active",
                        "已配对 active 设备可绑定审批模式和工具白名单，Web/API/桌面入口会合并该策略。"),
                new PolicyResolutionLayerView(60, "task", "task", "AgentTask.metadata", "active",
                        "任务 metadata 可携带本次实际审批模式、已批准工具和恢复上下文，优先反映单次任务授权。"),
                new PolicyResolutionLayerView(70, "agent-role", "agent", "clawagent.agents.policies", "active",
                        "Agent 角色策略作为配置模板参与合并，适合限制 coder、reviewer 等角色默认工具边界。"),
                new PolicyResolutionLayerView(80, "agent-metadata", "agent", "agent.permissionMode/agent.approvedToolIds", "active",
                        "调度层可在单个子 Agent metadata 中声明更严格的工具权限，不能放宽用户或设备策略。"),
                new PolicyResolutionLayerView(90, "agent-isolation", "agent", "agent.isolation", "active",
                        "只读子 Agent 强制 ask 并清空高危批准，ToolExecutionGuard 会拦截非 low 风险工具。"),
                new PolicyResolutionLayerView(100, "tool-enforcement", "tool", "execute/filesystem guard", "active",
                        "底层工具最终执行 allowed roots、敏感路径和风险分类校验，不能被页面策略绕过。")
        );
        List<String> pending = List.of(
                "企业级角色/组织/通道/设备权限矩阵",
                "字段级可视化策略编辑表单"
        );
        return new PolicySnapshotView(approval, permission, resolutionOrder, rules, pending);
    }

    private boolean approvalRequiresMediumOrUnknownConfirmation(ApprovalPolicy policy) {
        return !policy.isFullAccess();
    }

    private List<String> localAllowedRoots(ClawAgentProperties.Local local) {
        List<String> roots = new ArrayList<>();
        addUnique(roots, firstNonBlank(local.getWorkspaceRoot(), ".clawagent/workspace"));
        normalizeConfigList(local.getAllowedRoots()).forEach(root -> addUnique(roots, root));
        return roots;
    }

    private void syncLocalToolkitEnv(ClawAgentProperties.Local local) {
        List<String> allowedRoots = localAllowedRoots(local);
        String defaultCwd = firstNonBlank(local.getWorkspaceRoot(), ".clawagent/workspace");
        List<String> sensitivePathPatterns = normalizeConfigList(local.getSensitivePathPatterns());
        // 本地配置页是用户入口；保存时同步到底层工具 env，避免 execute/filesystem 使用旧的 allowed roots。
        syncToolEnv(ToolkitRegistry.TOOL_EXECUTE, allowedRoots, defaultCwd, Map.of(
                "SENSITIVE_PATH_PATTERNS", String.join(";", sensitivePathPatterns)));
        syncToolEnv(ToolkitRegistry.TOOL_FILESYSTEM, allowedRoots, defaultCwd, Map.of(
                "IGNORED_PATTERNS", String.join(";", normalizeConfigList(local.getIgnorePatterns())),
                "BLOCKED_PATTERNS", String.join(";", sensitivePathPatterns)));
    }

    private void syncToolEnv(String toolKey, List<String> allowedRoots, String defaultCwd, Map<String, String> extras) {
        ClawAgentProperties.Tool tool = properties.getToolkit().getTools().computeIfAbsent(toolKey, ignored -> new ClawAgentProperties.Tool());
        Map<String, String> env = new LinkedHashMap<>(tool.getEnv());
        env.put("ALLOWED_ROOTS", String.join(";", allowedRoots));
        env.put("DEFAULT_CWD", defaultCwd);
        extras.forEach(env::put);
        tool.setEnv(env);
    }

    private LocalHealthView buildLocalHealthView(boolean deep) {
        List<LocalHealthItemView> items = new ArrayList<>();
        items.add(pathHealth("workspace", "默认工作区", properties.getLocal().getWorkspaceRoot(), true));
        items.add(pathHealth("config", "本地配置文件", runtimeConfigPath().toString(), false));
        items.add(pathHealth("sqlite", "SQLite 数据库", properties.getPersistence().getSqlite().getPath(), false));
        items.add(modelHealth());
        if (deep) {
            items.add(modelConnectivityHealth());
        }
        items.add(toolHealth());
        items.add(allowedRootsHealth());
        items.add(executePathHealth());
        items.add(workerJarHealth());
        items.add(defaultShellHealth());
        items.add(permissionModeHealth());
        items.add(sensitivePathHealth());
        items.add(localRuntimeStorageHealth());
        items.add(mcpHealth());
        items.add(skillHealth());
        String status = items.stream().anyMatch(item -> "error".equals(item.status())) ? "DOWN"
                : items.stream().anyMatch(item -> "warning".equals(item.status())) ? "DEGRADED" : "UP";
        return new LocalHealthView(status, items);
    }

    private LocalHealthItemView pathHealth(String key, String label, String rawPath, boolean requireDirectory) {
        try {
            Path path = normalizeLocalPath(rawPath);
            boolean exists = requireDirectory ? Files.isDirectory(path) : Files.exists(path);
            Path writableTarget = exists ? path : path.getParent();
            boolean writable = writableTarget != null && Files.exists(writableTarget) && Files.isWritable(writableTarget);
            if (exists && writable) {
                return new LocalHealthItemView(key, label, "ok", "路径可用", path.toString());
            }
            if (!exists && writable) {
                return new LocalHealthItemView(key, label, "warning", "路径尚未创建，但父目录可写", path.toString());
            }
            return new LocalHealthItemView(key, label, "error", "路径不存在或不可写", path.toString());
        } catch (Exception ex) {
            return new LocalHealthItemView(key, label, "error", "路径配置无效", rawPath + "；" + ex.getMessage());
        }
    }

    private LocalHealthItemView modelHealth() {
        ClawAgentProperties.ModelConfig model = properties.getModels().get(properties.getModel().getDefault());
        if (model == null) {
            return new LocalHealthItemView("model", "模型配置", "error", "未找到默认模型配置", properties.getModel().getDefault());
        }
        ModelConfigView view = toModelConfigView(model);
        if (!view.apiKeyConfigured()) {
            return new LocalHealthItemView("model", "模型配置", "warning", "模型 API Key 未配置", view.provider() + " / " + view.model());
        }
        if (view.baseUrl() == null || view.baseUrl().isBlank() || view.model() == null || view.model().isBlank()) {
            return new LocalHealthItemView("model", "模型配置", "error", "模型地址或名称缺失", view.provider() + " / " + view.baseUrl());
        }
        return new LocalHealthItemView("model", "模型配置", "ok", "模型配置已填写", view.provider() + " / " + view.model());
    }

    private LocalHealthItemView modelConnectivityHealth() {
        ClawAgentProperties.ModelConfig model = properties.getModels().get(properties.getModel().getDefault());
        if (model == null) {
            return new LocalHealthItemView("model-connectivity", "模型连通", "error", "未找到默认模型配置", properties.getModel().getDefault());
        }
        ModelConfigView view = toModelConfigView(model);
        String apiKey = modelApiKeyForHealth(model);
        if (apiKey.isBlank()) {
            return new LocalHealthItemView("model-connectivity", "模型连通", "warning", "API Key 未配置或环境变量不可用", view.provider() + " / " + view.model());
        }
        if (view.baseUrl() == null || view.baseUrl().isBlank() || view.model() == null || view.model().isBlank()) {
            return new LocalHealthItemView("model-connectivity", "模型连通", "error", "模型地址或名称缺失", view.provider() + " / " + view.baseUrl());
        }
        // 深度检查会真实访问模型服务，只在用户主动点击时触发，避免配置页普通刷新被外部网络拖慢。
        ModelApiTestResponse response = testModelApi(new ModelApiTestRequest(
                view.provider(),
                view.baseUrl(),
                view.model(),
                apiKey,
                "请回复：模型连接正常。",
                view.temperature(),
                Math.min(Math.max(view.timeoutSeconds(), 1), 15)
        ));
        String detail = view.provider() + " / " + view.model() + " / "
                + (response.statusCode() > 0 ? "HTTP " + response.statusCode() : "未收到 HTTP 响应")
                + " / " + response.elapsedMs() + "ms";
        if (response.success()) {
            return new LocalHealthItemView("model-connectivity", "模型连通", "ok", "模型连通正常", detail);
        }
        return new LocalHealthItemView(
                "model-connectivity",
                "模型连通",
                "error",
                response.message(),
                firstNonBlank(preview(response.rawError(), 500), detail));
    }

    private String modelApiKeyForHealth(ClawAgentProperties.ModelConfig model) {
        String apiKey = firstNonBlank(model.getApiKey(), "");
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        String envName = firstNonBlank(model.getApiKeyEnv(), "");
        if (!envName.isBlank()) {
            return firstNonBlank(System.getenv(envName), "");
        }
        return "";
    }

    private LocalHealthItemView toolHealth() {
        List<String> required = List.of(
                "builtin.execute.command",
                "builtin.process.start",
                "builtin.process.status",
                "builtin.process.logs",
                "builtin.process.stop",
                "builtin.filesystem.read_text_file",
                "builtin.filesystem.write_file",
                "builtin.todo.create_plan",
                "builtin.todo.update_item"
        );
        List<String> missing = required.stream()
                .filter(toolId -> toolRegistry.find(toolId).isEmpty())
                .toList();
        if (missing.isEmpty()) {
            return new LocalHealthItemView("tools", "内置工具", "ok", "本地行动工具已注册", "execute/process/filesystem/todo");
        }
        return new LocalHealthItemView("tools", "内置工具", "error", "关键内置工具缺失", String.join(", ", missing));
    }

    private LocalHealthItemView executePathHealth() {
        ClawAgentProperties.Tool executeTool = properties.getToolkit().getTools().get(ToolkitRegistry.TOOL_EXECUTE);
        Map<String, String> env = executeTool == null || executeTool.getEnv() == null ? Map.of() : executeTool.getEnv();
        String defaultCwd = firstNonBlank(env.get("DEFAULT_CWD"), ".");
        String roots = firstNonBlank(env.get("ALLOWED_ROOTS"), ".");
        Path cwd = normalizeLocalPath(defaultCwd);
        List<Path> allowedRoots = Arrays.stream(roots.split("[;,]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(this::normalizeLocalPath)
                .toList();
        boolean allowed = allowedRoots.stream().anyMatch(cwd::startsWith);
        if (!allowed) {
            return new LocalHealthItemView("execute-path", "执行目录", "error", "DEFAULT_CWD 不在 ALLOWED_ROOTS 内",
                    "cwd=" + cwd + "；allowedRoots=" + allowedRoots);
        }
        return new LocalHealthItemView("execute-path", "执行目录", "ok", "执行目录在允许范围内",
                "cwd=" + cwd + "；allowedRoots=" + allowedRoots);
    }

    private LocalHealthItemView workerJarHealth() {
        ClawAgentProperties.Tool executeTool = properties.getToolkit().getTools().get(ToolkitRegistry.TOOL_EXECUTE);
        Map<String, String> env = executeTool == null || executeTool.getEnv() == null ? Map.of() : executeTool.getEnv();
        if (!Boolean.parseBoolean(firstNonBlank(env.get("WORKER_ENABLED"), "false"))) {
            return new LocalHealthItemView("worker-jar", "隔离 Worker", "ok", "隔离 worker 未启用", "WORKER_ENABLED=false");
        }
        String configured = firstNonBlank(env.get("WORKER_JAR"), "claw-agent-worker/target/claw-agent-worker-1.0.0-SNAPSHOT.jar");
        List<Path> checkedPaths = workerJarCandidates(configured);
        return checkedPaths.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> new LocalHealthItemView("worker-jar", "隔离 Worker", "ok", "worker jar 可用",
                        "WORKER_JAR=" + configured + "；resolved=" + path))
                .orElseGet(() -> new LocalHealthItemView("worker-jar", "隔离 Worker", "error",
                        "worker jar 不存在",
                        "WORKER_JAR=" + configured + "；user.dir=" + Path.of("").toAbsolutePath().normalize()
                                + "；checked=" + checkedPaths
                                + "；请先执行 mvn -pl claw-agent-worker -DskipTests package，或配置绝对路径"));
    }

    private List<Path> workerJarCandidates(String configured) {
        Path configuredPath = Path.of(configured);
        List<Path> candidates = new ArrayList<>();
        Path direct = configuredPath.toAbsolutePath().normalize();
        candidates.add(direct);
        if (!configuredPath.isAbsolute()) {
            Path current = Path.of("").toAbsolutePath().normalize();
            while (current != null) {
                Path candidate = current.resolve(configuredPath).normalize();
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
                // 本地开发可能从模块目录或 IDE 启动，健康检查和执行器保持同一套向上查找规则。
                current = current.getParent();
            }
        }
        return candidates;
    }

    private LocalHealthItemView allowedRootsHealth() {
        List<String> roots = localAllowedRoots(properties.getLocal());
        List<Path> rootPaths = roots.stream().map(this::normalizeLocalPath).toList();
        List<Path> missing = rootPaths.stream().filter(path -> !Files.exists(path)).toList();
        if (rootPaths.isEmpty()) {
            return new LocalHealthItemView("allowed-roots", "允许访问目录", "error", "未配置 allowed roots", "");
        }
        if (!missing.isEmpty()) {
            return new LocalHealthItemView("allowed-roots", "允许访问目录", "warning", "部分目录尚未创建", "missing=" + missing + "；all=" + rootPaths);
        }
        return new LocalHealthItemView("allowed-roots", "允许访问目录", "ok", "允许访问目录可用", rootPaths.toString());
    }

    private LocalHealthItemView defaultShellHealth() {
        String shell = firstNonBlank(properties.getLocal().getDefaultShell(), defaultShellName());
        List<String> probe = shellProbeCommand(shell);
        if (probe.isEmpty()) {
            return new LocalHealthItemView("default-shell", "默认 Shell", "error", "默认 Shell 配置无效", shell);
        }
        try {
            Process process = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(3, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return new LocalHealthItemView("default-shell", "默认 Shell", "warning", "Shell 探测超时", String.join(" ", probe));
            }
            int exitCode = process.exitValue();
            return new LocalHealthItemView(
                    "default-shell",
                    "默认 Shell",
                    exitCode == 0 ? "ok" : "warning",
                    exitCode == 0 ? "Shell 可执行" : "Shell 可执行但探测返回非 0",
                    shell + "；exitCode=" + exitCode);
        } catch (Exception ex) {
            return new LocalHealthItemView("default-shell", "默认 Shell", "error", "Shell 不可执行", shell + "；" + ex.getMessage());
        }
    }

    private LocalHealthItemView permissionModeHealth() {
        String mode = normalizePermissionMode(properties.getLocal().getPermissionMode());
        String summary = switch (mode) {
            case "auto" -> "自动批准高危工具，仍保留风险分类和路径限制";
            case "full" -> "完全访问模式，适合受信本机环境";
            case "custom" -> "自定义审批白名单模式";
            default -> "请求批准模式，高危工具需要用户确认";
        };
        return new LocalHealthItemView("permission-mode", "权限模式", "ok", summary, mode);
    }

    private LocalHealthItemView sensitivePathHealth() {
        List<String> patterns = normalizeConfigList(properties.getLocal().getSensitivePathPatterns());
        if (patterns.isEmpty()) {
            return new LocalHealthItemView(
                    "sensitive-paths",
                    "敏感路径",
                    "warning",
                    "未配置敏感路径保护",
                    "filesystem 不会额外拦截敏感文件，execute 也不会因敏感路径升高风险");
        }
        return new LocalHealthItemView(
                "sensitive-paths",
                "敏感路径",
                "ok",
                "敏感路径保护已启用",
                String.join(", ", patterns));
    }

    private LocalHealthItemView localRuntimeStorageHealth() {
        Path root = runtimeConfigRoot();
        List<Path> requiredDirs = List.of(
                root.resolve("backups").resolve("filesystem"),
                root.resolve("processes")
        );
        List<Path> blocked = requiredDirs.stream()
                .filter(path -> !isDirectoryOrParentWritable(path))
                .toList();
        if (blocked.isEmpty()) {
            return new LocalHealthItemView(
                    "local-runtime-storage",
                    "运行存储",
                    "ok",
                    "文件备份和进程表目录可写",
                    requiredDirs.toString());
        }
        return new LocalHealthItemView(
                "local-runtime-storage",
                "运行存储",
                "error",
                "文件备份或进程表目录不可写",
                "blocked=" + blocked + "；root=" + root);
    }

    private boolean isDirectoryOrParentWritable(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return Files.isWritable(normalized);
        }
        // 目录尚未创建时，只要最近存在的父目录可写，后续 write_file/process 工具就能按需创建运行目录。
        for (Path current = normalized.getParent(); current != null; current = current.getParent()) {
            if (Files.exists(current)) {
                return Files.isDirectory(current) && Files.isWritable(current);
            }
        }
        return false;
    }

    private String normalizePermissionMode(String value) {
        String mode = firstNonBlank(value, "ask").trim().toLowerCase(Locale.ROOT);
        return List.of("ask", "auto", "full", "custom").contains(mode) ? mode : "ask";
    }

    private String defaultShellName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "powershell" : "sh";
    }

    private List<String> shellProbeCommand(String shell) {
        String normalized = nullToEmpty(shell).trim().toLowerCase(Locale.ROOT);
        // 只做轻量探测，不执行用户命令；真实工具执行仍由 execute/process 工具独立处理。
        if (normalized.equals("powershell") || normalized.equals("powershell.exe")) {
            return List.of("powershell", "-NoProfile", "-Command", "Write-Output ok");
        }
        if (normalized.equals("pwsh") || normalized.equals("pwsh.exe")) {
            return List.of("pwsh", "-NoProfile", "-Command", "Write-Output ok");
        }
        if (normalized.equals("cmd") || normalized.equals("cmd.exe")) {
            return List.of("cmd", "/c", "echo ok");
        }
        if (normalized.equals("sh") || normalized.endsWith("/sh")) {
            return List.of(shell, "-lc", "echo ok");
        }
        if (normalized.equals("bash") || normalized.endsWith("/bash")) {
            return List.of(shell, "-lc", "echo ok");
        }
        return List.of();
    }

    private LocalHealthItemView mcpHealth() {
        List<McpServerRegistration> registrations = mcpRegistry.list();
        long connected = registrations.stream().filter(item -> item.status() != null && "CONNECTED".equals(item.status().name())).count();
        String status = registrations.isEmpty() ? "warning" : connected > 0 ? "ok" : "warning";
        String summary = registrations.isEmpty() ? "未配置 MCP Server" : "MCP Server " + connected + "/" + registrations.size() + " 已连接";
        return new LocalHealthItemView("mcp", "MCP", status, summary, "enabled=" + properties.getMcp().isEnabled());
    }

    private LocalHealthItemView skillHealth() {
        List<SkillRegistration> registrations = skillRegistry.list();
        long enabled = registrations.stream()
                .filter(item -> item.manifest() != null && item.manifest().enabled())
                .count();
        String status = registrations.isEmpty() ? "warning" : enabled > 0 ? "ok" : "warning";
        String summary = registrations.isEmpty() ? "未安装 Skill" : "Skill " + enabled + "/" + registrations.size() + " 已启用";
        return new LocalHealthItemView("skill", "Skill", status, summary, "enabled=" + properties.getSkills().isEnabled());
    }

    private Path normalizeLocalPath(String rawPath) {
        String value = rawPath == null || rawPath.isBlank() ? "." : rawPath.trim();
        return Path.of(value).toAbsolutePath().normalize();
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

    private List<String> normalizeConfigList(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String item = value == null ? "" : value.trim();
            if (!item.isBlank() && !normalized.contains(item)) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private Map<String, List<String>> normalizeConfigMap(Map<String, List<String>> values) {
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        if (values == null) {
            return normalized;
        }
        values.forEach((key, commands) -> {
            String path = key == null ? "" : key.trim();
            List<String> cleanCommands = normalizeConfigList(commands);
            if (!path.isBlank() && !cleanCommands.isEmpty()) {
                normalized.put(path, cleanCommands);
            }
        });
        return normalized;
    }

    private Map<String, List<String>> copyConfigMap(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (values == null) {
            return copy;
        }
        values.forEach((key, commands) -> copy.put(key, List.copyOf(commands)));
        return copy;
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
        yaml.append("    default: ").append(yamlScalar(defaultModel)).append('\n');
        if (properties.getModel().getMemoryModel() != null && !properties.getModel().getMemoryModel().isBlank()) {
            yaml.append("    memory-model: ").append(yamlScalar(properties.getModel().getMemoryModel())).append('\n');
        }
        if (properties.getModel().getVisionModel() != null && !properties.getModel().getVisionModel().isBlank()) {
            yaml.append("    vision-model: ").append(yamlScalar(properties.getModel().getVisionModel())).append('\n');
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
            yaml.append("      vision: ").append(modelConfig.isVision()).append('\n');
        }
        ClawAgentProperties.Embedding embedding = properties.getMemory().getVector().getEmbedding();
        yaml.append("  memory:\n");
        ClawAgentProperties.Extraction extraction = properties.getMemory().getExtraction();
        yaml.append("    extraction:\n");
        yaml.append("      enabled: ").append(extraction.isEnabled()).append('\n');
        yaml.append("      mode: ").append(yamlScalar(extraction.getMode())).append('\n');
        yaml.append("      interval-seconds: ").append(extraction.getIntervalSeconds()).append('\n');
        yaml.append("      batch-size: ").append(extraction.getBatchSize()).append('\n');
        ClawAgentProperties.Governance governance = properties.getMemory().getGovernance();
        yaml.append("    governance:\n");
        yaml.append("      stale-after-days: ").append(governance.getStaleAfterDays()).append('\n');
        yaml.append("      very-stale-after-days: ").append(governance.getVeryStaleAfterDays()).append('\n');
        yaml.append("      auto-archive-enabled: ").append(governance.isAutoArchiveEnabled()).append('\n');
        yaml.append("      archive-after-days: ").append(governance.getArchiveAfterDays()).append('\n');
        yaml.append("      archive-below-quality: ").append(governance.getArchiveBelowQuality()).append('\n');
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
        ClawAgentProperties.Cost cost = properties.getCost();
        yaml.append("  cost:\n");
        yaml.append("    currency: ").append(yamlScalar(firstNonBlank(cost.getCurrency(), "USD"))).append('\n');
        yaml.append("    rules:\n");
        if (cost.getRules().isEmpty()) {
            yaml.append("      {}\n");
        } else {
            cost.getRules().forEach((model, price) -> {
                yaml.append("      ").append(yamlKey(model)).append(":\n");
                yaml.append("        input-per-million: ").append(Math.max(0, price.getInputPerMillion())).append('\n');
                yaml.append("        output-per-million: ").append(Math.max(0, price.getOutputPerMillion())).append('\n');
                if (price.getCurrency() != null && !price.getCurrency().isBlank()) {
                    yaml.append("        currency: ").append(yamlScalar(price.getCurrency())).append('\n');
                }
            });
        }
        ClawAgentProperties.Local local = properties.getLocal();
        yaml.append("  local:\n");
        yaml.append("    workspace-root: ").append(yamlScalar(local.getWorkspaceRoot())).append('\n');
        yaml.append("    default-shell: ").append(yamlScalar(local.getDefaultShell())).append('\n');
        yaml.append("    permission-mode: ").append(yamlScalar(normalizePermissionMode(local.getPermissionMode()))).append('\n');
        yaml.append("    approved-tool-ids:\n");
        appendYamlList(yaml, local.getApprovedToolIds(), "      ");
        yaml.append("    allowed-roots:\n");
        appendYamlList(yaml, localAllowedRoots(local), "      ");
        yaml.append("    recent-projects:\n");
        appendYamlList(yaml, local.getRecentProjects(), "      ");
        yaml.append("    test-commands:\n");
        appendYamlList(yaml, local.getTestCommands(), "      ");
        yaml.append("    project-test-commands:\n");
        appendYamlMapList(yaml, local.getProjectTestCommands(), "      ");
        yaml.append("    ignore-patterns:\n");
        appendYamlList(yaml, local.getIgnorePatterns(), "      ");
        yaml.append("    sensitive-path-patterns:\n");
        appendYamlList(yaml, local.getSensitivePathPatterns(), "      ");
        yaml.append("  toolkit:\n");
        yaml.append("    tools:\n");
        appendToolkitToolYaml(yaml, ToolkitRegistry.TOOL_EXECUTE);
        appendToolkitToolYaml(yaml, ToolkitRegistry.TOOL_FILESYSTEM);
        return yaml.toString();
    }

    private void appendToolkitToolYaml(StringBuilder yaml, String toolKey) {
        ClawAgentProperties.Tool tool = properties.getToolkit().getTools().get(toolKey);
        if (tool == null) {
            return;
        }
        yaml.append("      ").append(yamlKey(toolKey)).append(":\n");
        yaml.append("        enabled: ").append(tool.isEnabled()).append('\n');
        yaml.append("        env:\n");
        if (tool.getEnv().isEmpty()) {
            yaml.append("          {}\n");
            return;
        }
        tool.getEnv().forEach((key, value) ->
                yaml.append("          ").append(yamlKey(key)).append(": ").append(yamlScalar(value)).append('\n'));
    }

    private void appendYamlList(StringBuilder yaml, List<String> values, String indent) {
        List<String> normalized = normalizeConfigList(values);
        if (normalized.isEmpty()) {
            yaml.append(indent).append("[]\n");
            return;
        }
        for (String value : normalized) {
            yaml.append(indent).append("- ").append(yamlScalar(value)).append('\n');
        }
    }

    private void appendYamlMapList(StringBuilder yaml, Map<String, List<String>> values, String indent) {
        Map<String, List<String>> normalized = normalizeConfigMap(values);
        if (normalized.isEmpty()) {
            yaml.append(indent).append("{}\n");
            return;
        }
        normalized.forEach((key, commands) -> {
            yaml.append(indent).append(yamlScalar(key)).append(":\n");
            appendYamlList(yaml, commands, indent + "  ");
        });
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

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            // 外部 Skill 安装工具按字符串参数工作，这里只传有效字段，避免空字符串覆盖 frontmatter。
            target.put(key, value.trim());
        }
    }

    private String yamlKey(String value) {
        String key = value == null || value.isBlank() ? "default" : value;
        return key.matches("[A-Za-z0-9_.-]+") ? key : yamlScalar(key);
    }

    private String yamlScalar(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
