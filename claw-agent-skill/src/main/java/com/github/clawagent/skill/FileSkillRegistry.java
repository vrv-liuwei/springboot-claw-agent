package com.github.clawagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.clawagent.spi.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于本地目录的 Skill 注册表。
 * 目录布局为 .clawagent/skills/<skillId>/manifest.json，可直接备份、迁移或纳入后续同步流程。
 */
public class FileSkillRegistry implements SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(FileSkillRegistry.class);

    /** Skill manifest 的 JSON 读写器，保存时使用缩进格式便于人工查看。 */
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** Skill 根目录列表；启动时会扫描每个目录下的 manifest.json。 */
    private final List<Path> roots;
    /** 统一工具注册表，启用的 Skill 会注册为 skill.* 工具。 */
    private final AgentToolRegistry toolRegistry;
    /** Script Skill 的进程执行器；Spring 环境会注入 worker-backed 实现，普通环境使用默认直接执行。 */
    private final SkillProcessExecutor processExecutor;
    /** 当前 JVM 内已加载的 Skill 注册状态，key 为 skillId。 */
    private final Map<String, SkillRegistration> skills = new LinkedHashMap<>();

    public FileSkillRegistry(Path root) {
        this(root, null);
    }

    public FileSkillRegistry(Path root, AgentToolRegistry toolRegistry) {
        this(root == null ? List.of() : List.of(root), toolRegistry);
    }

    public FileSkillRegistry(List<Path> roots, AgentToolRegistry toolRegistry) {
        this(roots, toolRegistry, null);
    }

    public FileSkillRegistry(List<Path> roots, AgentToolRegistry toolRegistry, SkillProcessExecutor processExecutor) {
        this.roots = roots == null || roots.isEmpty() ? List.of(Path.of(".clawagent/skills")) : List.copyOf(roots);
        this.toolRegistry = toolRegistry;
        this.processExecutor = processExecutor;
        load();
    }

    @Override
    public synchronized SkillRegistration install(SkillPackage skillPackage) {
        if (skillPackage == null) {
            throw new IllegalArgumentException("Skill package 不能为空");
        }
        if (skillPackage.manifest() == null) {
            if (skillPackage.content() == null || skillPackage.content().isBlank()) {
                throw new IllegalArgumentException("Skill manifest 或 SKILL.md 内容不能为空");
            }
            // 兼容 Codex/Claude 风格 SKILL.md：没有 manifest.json 时自动转换成 ClawAgent manifest。
            skillPackage = SkillPackageConverter.fromSkillMarkdown(skillPackage.content(), null, null, null, true, Map.of());
        }
        SkillManifest manifest = normalize(skillPackage.manifest(), skillPackage.manifest().enabled());
        Path skillDir = skillDir(primaryRoot(), manifest.id());
        try {
            writeSkillPackage(skillDir, manifest, skillPackage);
            SkillRegistration registration = new SkillRegistration(manifest, Instant.now(), skillDir.toString(), "INSTALLED", "installed");
            skills.put(manifest.id(), registration);
            syncTools(manifest, skillDir);
            log.info("skill installed id={} path={}", manifest.id(), skillDir);
            return registration;
        } catch (IOException e) {
            throw new IllegalStateException("Skill 保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public synchronized SkillRegistration update(String skillId, SkillPackage skillPackage) {
        SkillRegistration current = require(skillId);
        SkillPackage safePackage = skillPackage == null
                ? new SkillPackage(current.manifest(), null)
                : skillPackage;
        SkillManifest sourceManifest = safePackage.manifest() == null ? current.manifest() : safePackage.manifest();
        // 更新接口不允许通过 body 改 Skill ID，避免管理台编辑 manifest 时误创建另一个目录。
        SkillManifest manifest = normalize(new SkillManifest(
                current.manifest().id(),
                sourceManifest.name(),
                sourceManifest.version(),
                sourceManifest.description(),
                sourceManifest.enabled(),
                sourceManifest.entrypoint(),
                sourceManifest.tools(),
                sourceManifest.permissions(),
                sourceManifest.metadata()), sourceManifest.enabled());
        Path skillDir = Path.of(current.installedPath()).normalize();
        if (!isUnderSkillRoot(skillDir)) {
            throw new IllegalStateException("Skill 目录不在允许根目录内：" + skillDir);
        }
        try {
            writeSkillPackage(skillDir, manifest, safePackage);
            SkillRegistration registration = new SkillRegistration(
                    manifest,
                    current.installedAt(),
                    skillDir.toString(),
                    manifest.enabled() ? "UPDATED" : "DISABLED",
                    "updated");
            skills.put(manifest.id(), registration);
            syncTools(manifest, skillDir);
            log.info("skill updated id={} path={}", manifest.id(), skillDir);
            return registration;
        } catch (IOException e) {
            throw new IllegalStateException("Skill 更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public synchronized SkillRegistration enable(String skillId) {
        return setEnabled(skillId, true);
    }

    @Override
    public synchronized SkillRegistration disable(String skillId) {
        return setEnabled(skillId, false);
    }

    @Override
    public synchronized boolean delete(String skillId) {
        SkillRegistration current = skills.remove(skillId);
        if (current == null) {
            return false;
        }
        unloadSkillTools(skillId);
        Path skillDir = Path.of(current.installedPath()).normalize();
        if (!isUnderSkillRoot(skillDir)) {
            throw new IllegalStateException("Skill 目录不在允许根目录内：" + skillDir);
        }
        deleteDirectory(skillDir);
        log.warn("skill deleted id={} path={}", skillId, skillDir);
        return true;
    }

    @Override
    public synchronized Optional<SkillRegistration> find(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    @Override
    public synchronized List<SkillRegistration> list() {
        return skills.values().stream()
                .sorted(Comparator.comparing(registration -> registration.manifest().id()))
                .toList();
    }

    @Override
    public synchronized List<SkillRegistration> refresh() {
        // 后台修改 Skill 目录后，刷新必须先卸载旧工具，避免禁用或删除的工具继续留在运行态。
        unloadAllSkillTools();
        skills.clear();
        load();
        return list();
    }

    private SkillRegistration setEnabled(String skillId, boolean enabled) {
        SkillRegistration current = require(skillId);
        SkillManifest manifest = normalize(current.manifest(), enabled);
        Path skillDir = Path.of(current.installedPath());
        try {
            objectMapper.writeValue(skillDir.resolve("manifest.json").toFile(), manifest);
            SkillRegistration registration = new SkillRegistration(
                    manifest,
                    current.installedAt(),
                    skillDir.toString(),
                    enabled ? "ENABLED" : "DISABLED",
                    enabled ? "enabled" : "disabled");
            skills.put(manifest.id(), registration);
            syncTools(manifest, skillDir);
            return registration;
        } catch (IOException e) {
            throw new IllegalStateException("Skill 状态保存失败：" + e.getMessage(), e);
        }
    }

    private void writeSkillPackage(Path skillDir, SkillManifest manifest, SkillPackage skillPackage) throws IOException {
        Files.createDirectories(skillDir);
        objectMapper.writeValue(skillDir.resolve("manifest.json").toFile(), manifest);
        if (skillPackage.content() != null && !skillPackage.content().isBlank()) {
            // 外部 Skill 的入口通常是 SKILL.md，必须按 manifest.entrypoint 落盘，否则脚本说明和触发内容会错位。
            Files.writeString(safeResolve(skillDir, entrypointOrDefault(manifest)), skillPackage.content(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, String> resource : skillPackage.resourceFiles().entrySet()) {
            Path resourcePath = safeResolve(skillDir, resource.getKey());
            // GitHub Skill 的 scripts/references/assets 等资源随包保存，后续执行器可直接按相对路径读取。
            Files.createDirectories(resourcePath.getParent());
            Files.writeString(resourcePath, resource.getValue() == null ? "" : resource.getValue(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, byte[]> resource : skillPackage.binaryResourceFiles().entrySet()) {
            Path resourcePath = safeResolve(skillDir, resource.getKey());
            // Java Skill 的 lib/*.jar 必须按字节保存，不能经过字符串转换。
            Files.createDirectories(resourcePath.getParent());
            Files.write(resourcePath, resource.getValue() == null ? new byte[0] : resource.getValue());
        }
    }

    private void load() {
        try {
            for (Path root : roots) {
                Files.createDirectories(root);
            }
            bootstrapSystemSkills();
            List<Path> manifests = new ArrayList<>();
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (var stream = Files.list(root)) {
                    stream.filter(Files::isDirectory)
                            .map(path -> path.resolve("manifest.json"))
                            .filter(Files::exists)
                            .forEach(manifests::add);
                }
            }
            for (Path manifestPath : manifests) {
                SkillManifest manifest = normalize(objectMapper.readValue(manifestPath.toFile(), SkillManifest.class), true);
                Path skillDir = manifestPath.getParent();
                String status = manifest.enabled() ? "INSTALLED" : "DISABLED";
                skills.put(manifest.id(), new SkillRegistration(manifest, Instant.now(), skillDir.toString(), status, "loaded"));
                syncTools(manifest, skillDir);
            }
            log.info("skill registry loaded count={} roots={}", skills.size(), roots);
        } catch (IOException e) {
            throw new IllegalStateException("Skill 注册表加载失败：" + e.getMessage(), e);
        }
    }

    private void bootstrapSystemSkills() throws IOException {
        for (SkillPackage skillPackage : SystemSkillCatalog.packages()) {
            SkillManifest manifest = normalize(skillPackage.manifest(), skillPackage.manifest().enabled());
            Path skillDir = skillDir(primaryRoot(), manifest.id());
            Path manifestPath = skillDir.resolve("manifest.json");
            if (Files.exists(manifestPath)) {
                continue;
            }
            Files.createDirectories(skillDir);
            objectMapper.writeValue(manifestPath.toFile(), manifest);
            String entrypoint = manifest.entrypoint() == null || manifest.entrypoint().isBlank()
                    ? "SKILL.md"
                    : manifest.entrypoint();
            Files.writeString(skillDir.resolve(entrypoint), skillPackage.content(), StandardCharsets.UTF_8);
            log.info("system skill bootstrapped id={} path={}", manifest.id(), skillDir);
        }
    }

    private SkillManifest normalize(SkillManifest manifest, boolean enabled) {
        String id = manifest.id() == null || manifest.id().isBlank() ? slug(manifest.name()) : manifest.id().trim();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Skill id/name 不能为空");
        }
        String name = manifest.name() == null || manifest.name().isBlank() ? id : manifest.name().trim();
        String version = manifest.version() == null || manifest.version().isBlank() ? "0.1.0" : manifest.version().trim();
        return new SkillManifest(
                id,
                name,
                version,
                nullToEmpty(manifest.description()),
                enabled,
                nullToEmpty(manifest.entrypoint()),
                manifest.tools(),
                manifest.permissions(),
                manifest.metadata());
    }

    private void syncTools(SkillManifest manifest) {
        syncTools(manifest, skillDir(primaryRoot(), manifest.id()));
    }

    private void syncTools(SkillManifest manifest, Path dir) {
        if (toolRegistry == null) {
            return;
        }
        String prefix = toolPrefix(manifest.id());
        toolRegistry.unregister("skill." + manifest.id());
        toolRegistry.unregisterByPrefix(prefix);
        if (!manifest.enabled()) {
            log.info("skill tools disabled skillId={}", manifest.id());
            return;
        }
        List<String> toolNames = manifest.tools().isEmpty() ? List.of("default") : manifest.tools();
        for (String toolName : toolNames) {
            String normalizedToolName = slug(toolName);
            String toolId = "default".equals(normalizedToolName)
                    ? "skill." + manifest.id()
                    : prefix + normalizedToolName;
            toolRegistry.registerOrReplace(new SkillAgentTool(manifest, normalizedToolName, toolId, dir, processExecutor));
            log.info("skill tool registered skillId={} toolId={}", manifest.id(), toolId);
        }
    }

    private SkillRegistration require(String skillId) {
        SkillRegistration registration = skills.get(skillId);
        if (registration == null) {
            throw new IllegalArgumentException("Skill 不存在：" + skillId);
        }
        return registration;
    }

    private void unloadAllSkillTools() {
        if (toolRegistry == null) {
            return;
        }
        for (String skillId : skills.keySet()) {
            unloadSkillTools(skillId);
        }
    }

    private void unloadSkillTools(String skillId) {
        if (toolRegistry == null) {
            return;
        }
        // 同时清理默认工具 skill.<id> 和子工具 skill.<id>.*，保证刷新、禁用、删除后的工具表和 manifest 一致。
        toolRegistry.unregister("skill." + skillId);
        toolRegistry.unregisterByPrefix(toolPrefix(skillId));
    }

    private Path primaryRoot() {
        return roots.get(0);
    }

    private Path skillDir(Path root, String skillId) {
        Path normalizedRoot = root.normalize();
        Path dir = normalizedRoot.resolve(skillId).normalize();
        if (!dir.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Skill id 越权：" + skillId);
        }
        return dir;
    }

    private Path safeResolve(Path root, String relativePath) {
        String path = relativePath == null || relativePath.isBlank() ? "README.md" : relativePath.replace('\\', '/');
        Path normalizedRoot = root.normalize();
        Path target = normalizedRoot.resolve(path).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Skill 文件路径越权：" + relativePath);
        }
        return target;
    }

    private boolean isUnderSkillRoot(Path skillDir) {
        for (Path root : roots) {
            if (skillDir.startsWith(root.normalize())) {
                return true;
            }
        }
        return false;
    }

    private void deleteDirectory(Path skillDir) {
        try (var stream = Files.walk(skillDir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Skill 删除失败：" + e.getMessage(), e);
        }
    }

    private String entrypointOrDefault(SkillManifest manifest) {
        return manifest.entrypoint() == null || manifest.entrypoint().isBlank() ? "README.md" : manifest.entrypoint();
    }

    private String toolPrefix(String skillId) {
        return "skill." + skillId + ".";
    }

    private String slug(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
