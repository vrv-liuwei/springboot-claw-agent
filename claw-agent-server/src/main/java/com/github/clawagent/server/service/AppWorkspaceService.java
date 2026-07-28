package com.github.clawagent.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.spring.ClawAgentProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AppWorkspaceService 维护个人 App 最近项目和当前项目。
 * 第一版使用本地 JSON 文件，避免为桌面工作区引入额外数据库迁移。
 */
@Service
public class AppWorkspaceService {
    private static final int MAX_RECENT = 50;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClawAgentProperties properties;

    public AppWorkspaceService(ClawAgentProperties properties) {
        this.properties = properties;
    }

    public synchronized AppWorkspace openWorkspace(String path) {
        Path root = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工作区目录不存在：" + root);
        }
        WorkspaceState state = readState();
        String id = workspaceId(root);
        String displayName = state.recent().stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .map(AppWorkspace::name)
                .orElseGet(() -> workspaceName(root));
        AppWorkspace workspace = new AppWorkspace(
                id,
                displayName,
                root.toString(),
                Instant.now().toString());
        List<AppWorkspace> recent = new ArrayList<>();
        recent.add(workspace);
        for (AppWorkspace item : state.recent()) {
            if (!item.id().equals(workspace.id())) {
                recent.add(item);
            }
            if (recent.size() >= MAX_RECENT) {
                break;
            }
        }
        writeState(new WorkspaceState(workspace.id(), recent));
        return workspace;
    }

    public synchronized List<AppWorkspace> recentWorkspaces() {
        return readState().recent();
    }

    public synchronized Optional<AppWorkspace> currentWorkspace() {
        WorkspaceState state = readState();
        return state.recent().stream()
                .filter(workspace -> workspace.id().equals(state.currentWorkspaceId()))
                .findFirst();
    }

    public synchronized AppWorkspace switchWorkspace(String workspaceId) {
        WorkspaceState state = readState();
        AppWorkspace workspace = state.recent().stream()
                .filter(item -> item.id().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工作区不存在：" + workspaceId));
        writeState(new WorkspaceState(workspace.id(), state.recent()));
        return workspace;
    }

    public synchronized AppWorkspace renameWorkspace(String workspaceId, String name) {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("项目名称不能为空");
        }
        WorkspaceState state = readState();
        List<AppWorkspace> recent = new ArrayList<>();
        AppWorkspace updated = null;
        for (AppWorkspace item : state.recent()) {
            if (item.id().equals(workspaceId)) {
                // 只修改 App 侧展示名，真实 workspace 根目录保持不变。
                updated = new AppWorkspace(item.id(), displayName, item.root(), item.lastOpenedAt());
                recent.add(updated);
            } else {
                recent.add(item);
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("工作区不存在：" + workspaceId);
        }
        writeState(new WorkspaceState(state.currentWorkspaceId(), recent));
        return updated;
    }

    public synchronized Map<String, String> enrichWorkspaceMetadata(String workspaceId, Map<String, String> metadata) {
        Map<String, String> result = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        String id = firstNonBlank(workspaceId, result.get("workspaceId"), result.get("workspace.id"));
        if (!id.isBlank()) {
            findWorkspace(id).ifPresent(workspace -> applyWorkspace(result, workspace));
        }
        if (id.isBlank() && !hasWorkspacePath(result)) {
            // 普通对话和计划模式可能没有显式传 workspaceId，这时使用 App 当前工作区作为默认项目目录。
            currentWorkspace().ifPresent(workspace -> applyWorkspace(result, workspace));
        }
        aliasWorkspace(result);
        return result;
    }

    public synchronized Optional<AppWorkspace> findWorkspace(String workspaceId) {
        return readState().recent().stream()
                .filter(workspace -> workspace.id().equals(workspaceId))
                .findFirst();
    }

    public synchronized boolean isRegisteredPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (AppWorkspace workspace : readState().recent()) {
            Path root = Path.of(workspace.root()).toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public Path dataDir() {
        return statePath().getParent().toAbsolutePath().normalize();
    }

    private void applyWorkspace(Map<String, String> metadata, AppWorkspace workspace) {
        metadata.put("workspaceId", workspace.id());
        metadata.put("workspaceName", workspace.name());
        metadata.put("workspaceRoot", workspace.root());
        aliasWorkspace(metadata);
    }

    private void aliasWorkspace(Map<String, String> metadata) {
        putIfPresent(metadata, "workspace.id", metadata.get("workspaceId"));
        putIfPresent(metadata, "workspace.name", metadata.get("workspaceName"));
        putIfPresent(metadata, "workspace.root", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "workspace.projectPath", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "projectPath", metadata.get("workspaceRoot"));
        putIfPresent(metadata, "activeProjectPath", metadata.get("workspaceRoot"));
    }

    private boolean hasWorkspacePath(Map<String, String> metadata) {
        return !firstNonBlank(
                metadata.get("activeProjectPath"),
                metadata.get("projectPath"),
                metadata.get("workspace.projectPath"),
                metadata.get("workspaceRoot"),
                metadata.get("workspace.root")).isBlank();
    }

    private WorkspaceState readState() {
        Path path = statePath();
        if (!Files.exists(path)) {
            return new WorkspaceState("", List.of());
        }
        try {
            WorkspaceState state = objectMapper.readValue(path.toFile(), WorkspaceState.class);
            return state == null ? new WorkspaceState("", List.of()) : state.safe();
        } catch (IOException e) {
            throw new IllegalStateException("读取 App 工作区失败：" + path, e);
        }
    }

    private void writeState(WorkspaceState state) {
        Path path = statePath();
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), state.safe());
        } catch (IOException e) {
            throw new IllegalStateException("保存 App 工作区失败：" + path, e);
        }
    }

    private Path statePath() {
        String sqlitePath = properties.getPersistence().getSqlite().getPath();
        Path databasePath = Path.of(sqlitePath == null || sqlitePath.isBlank() ? ".clawagent/clawagent.db" : sqlitePath);
        Path root = databasePath.getParent() == null ? Path.of(".clawagent") : databasePath.getParent();
        return root.resolve("app").resolve("workspaces.json").toAbsolutePath().normalize();
    }

    private String workspaceId(Path root) {
        return UUID.nameUUIDFromBytes(root.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String workspaceName(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.putIfAbsent(key, value);
        }
    }

    public record AppWorkspace(String id, String name, String root, String lastOpenedAt) {
    }

    public record WorkspaceState(String currentWorkspaceId, List<AppWorkspace> recent) {
        WorkspaceState safe() {
            return new WorkspaceState(
                    currentWorkspaceId == null ? "" : currentWorkspaceId,
                    recent == null ? List.of() : List.copyOf(recent));
        }
    }
}
