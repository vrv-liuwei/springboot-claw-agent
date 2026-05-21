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
    /** 当前 JVM 内已加载的 Skill 注册状态，key 为 skillId。 */
    private final Map<String, SkillRegistration> skills = new LinkedHashMap<>();

    public FileSkillRegistry(Path root) {
        this(root, null);
    }

    public FileSkillRegistry(Path root, AgentToolRegistry toolRegistry) {
        this(root == null ? List.of() : List.of(root), toolRegistry);
    }

    public FileSkillRegistry(List<Path> roots, AgentToolRegistry toolRegistry) {
        this.roots = roots == null || roots.isEmpty() ? List.of(Path.of(".clawagent/skills")) : List.copyOf(roots);
        this.toolRegistry = toolRegistry;
        load();
    }

    @Override
    public synchronized SkillRegistration install(SkillPackage skillPackage) {
        if (skillPackage == null || skillPackage.manifest() == null) {
            throw new IllegalArgumentException("Skill manifest 不能为空");
        }
        SkillManifest manifest = normalize(skillPackage.manifest(), skillPackage.manifest().enabled());
        Path skillDir = skillDir(primaryRoot(), manifest.id());
        try {
            Files.createDirectories(skillDir);
            objectMapper.writeValue(skillDir.resolve("manifest.json").toFile(), manifest);
            if (skillPackage.content() != null && !skillPackage.content().isBlank()) {
                Files.writeString(skillDir.resolve("README.md"), skillPackage.content(), StandardCharsets.UTF_8);
            }
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
    public synchronized SkillRegistration enable(String skillId) {
        return setEnabled(skillId, true);
    }

    @Override
    public synchronized SkillRegistration disable(String skillId) {
        return setEnabled(skillId, false);
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
            toolRegistry.registerOrReplace(new SkillAgentTool(manifest, normalizedToolName, toolId, dir));
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
