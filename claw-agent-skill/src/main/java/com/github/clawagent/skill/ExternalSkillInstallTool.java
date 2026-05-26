package com.github.clawagent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;
import com.github.clawagent.spi.AgentTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 Skill 安装工具。
 * 支持把 Codex/Claude 常见的 SKILL.md 或 GitHub 仓库自动转换成 ClawAgent 本地 Skill。
 */
public class ExternalSkillInstallTool implements AgentTool {
    private static final int REQUEST_TIMEOUT_MS = 60_000;
    private static final List<String> RESOURCE_DIRS = List.of("scripts", "references", "assets", "agents");
    private static final List<String> RESOURCE_FILES = List.of("runtime.conf", "runtime.conf.example", ".env.example", "README.md");
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExternalSkillInstallTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("sourceUrl", ToolDefinition.stringProperty("GitHub 仓库 URL、raw SKILL.md URL 或普通 SKILL.md URL；安装 GitHub 仓库时必须优先使用该参数，才能下载 scripts/references/assets 等资源"));
        schema.put("skillMd", ToolDefinition.stringProperty("SKILL.md 原文内容；仅在用户直接提供原文或无法访问仓库时使用，使用该参数无法自动下载 scripts 等仓库资源"));
        schema.put("id", ToolDefinition.stringProperty("可选：覆盖转换后的 Skill ID"));
        schema.put("name", ToolDefinition.stringProperty("可选：覆盖转换后的 Skill 名称"));
        schema.put("description", ToolDefinition.stringProperty("可选：覆盖转换后的 Skill 描述"));
        schema.put("overwrite", Map.of("type", "boolean", "description", "是否允许覆盖同名 Skill，默认 false"));
        return new ToolDefinition(
                "skill.skills-install.install",
                "Install External Skill",
                "安装 Codex/Claude/ClawAgent Skill。GitHub 仓库必须直接传 sourceUrl=仓库URL，这样才能自动查找 SKILL.md 并下载 scripts/references/assets/agents 等资源；只有用户直接提供 SKILL.md 原文时才使用 skillMd。会转换为 ClawAgent manifest.json。",
                "medium",
                ToolDefinition.objectSchema(schema, true, List.of()));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            boolean overwrite = Boolean.parseBoolean(call.arguments().getOrDefault("overwrite", "false"));
            List<SkillPackage> packages = resolvePackages(call);
            if (packages.isEmpty()) {
                return ToolResult.error("未找到可安装的 SKILL.md");
            }
            List<String> lines = new ArrayList<>();
            for (SkillPackage skillPackage : packages) {
                SkillManifest manifest = skillPackage.manifest();
                if (!overwrite && skillRegistry.find(manifest.id()).isPresent()) {
                    lines.add("skipped existing: " + manifest.id());
                    continue;
                }
                SkillRegistration registration = skillRegistry.install(skillPackage);
                lines.add("installed: " + registration.manifest().id()
                        + " path=" + registration.installedPath()
                        + " resources=" + skillPackage.resourceFiles().size());
            }
            return ToolResult.success(String.join("\n", lines));
        } catch (Exception e) {
            return ToolResult.error("安装外部 Skill 失败：" + e.getMessage());
        }
    }

    private List<SkillPackage> resolvePackages(ToolCall call) throws Exception {
        String skillMd = call.arguments().get("skillMd");
        String sourceUrl = call.arguments().get("sourceUrl");
        if (skillMd != null && !skillMd.isBlank()) {
            return List.of(convert(skillMd, call, sourceUrl));
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl 和 skillMd 至少需要一个");
        }
        if (isGithubRepositoryUrl(sourceUrl)) {
            return fromGithubRepository(sourceUrl, call);
        }
        return List.of(convert(fetchText(sourceUrl), call, sourceUrl));
    }

    private List<SkillPackage> fromGithubRepository(String sourceUrl, ToolCall call) throws Exception {
        GithubRepo repo = parseGithubRepo(sourceUrl);
        List<SkillPackage> result = new ArrayList<>();
        JsonNode tree = githubTree(repo, "main");
        if (tree == null) {
            tree = githubTree(repo, "master");
        }
        if (tree == null) {
            throw new IllegalArgumentException("无法读取 GitHub 仓库 tree：" + sourceUrl);
        }
        JsonNode treeItems = tree.path("tree");
        for (JsonNode item : treeItems) {
            String path = item.path("path").asText();
            if (!path.equals("SKILL.md") && !path.endsWith("/SKILL.md")) {
                continue;
            }
            String skillDir = skillDirectory(path);
            String idOverride = blankToNull(call.arguments().get("id"));
            if (idOverride == null) {
                // 仓库根目录只有一个 SKILL.md 时，用仓库名作为兜底 ID，避免无 frontmatter 时退化为 imported-skill。
                idOverride = skillDir.isBlank() ? repo.name : skillDir.substring(skillDir.lastIndexOf('/') + 1);
            }
            String rawUrl = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + repo.branch + "/" + path;
            Map<String, String> resourceFiles = downloadResourceFiles(repo, skillDir, treeItems);
            result.add(convert(fetchText(rawUrl), call, rawUrl, idOverride, resourceFiles));
        }
        return result;
    }

    private JsonNode githubTree(GithubRepo repo, String branch) throws Exception {
        repo.branch = branch;
        String url = "https://api.github.com/repos/" + repo.owner + "/" + repo.name + "/git/trees/" + branch + "?recursive=1";
        AgentHttpResponse response = AgentHttpClient.get(url, Map.of(
                "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28"), REQUEST_TIMEOUT_MS);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub API 返回 " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private SkillPackage convert(String content, ToolCall call, String sourceUrl) {
        return convert(content, call, sourceUrl, blankToNull(call.arguments().get("id")), Map.of());
    }

    private SkillPackage convert(String content, ToolCall call, String sourceUrl, String idOverride, Map<String, String> resourceFiles) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            metadata.put("sourceUrl", sourceUrl);
        }
        SkillPackage skillPackage = SkillPackageConverter.fromSkillMarkdown(
                content,
                idOverride,
                blankToNull(call.arguments().get("name")),
                blankToNull(call.arguments().get("description")),
                true,
                metadata,
                resourceFiles);
        return enrichKnownScriptExecutor(skillPackage);
    }

    private SkillPackage enrichKnownScriptExecutor(SkillPackage skillPackage) {
        SkillManifest manifest = skillPackage.manifest();
        if (!isAnySearchSkill(manifest, skillPackage.resourceFiles())) {
            return skillPackage;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(manifest.metadata());
        if (!metadata.containsKey("executor")) {
            // anysearch 官方 Skill 的核心能力在 scripts/anysearch_cli.*，默认文档执行器只会返回 SKILL.md。
            // 安装时发现脚本资源后，自动补成 script executor，确保 Agent 调用 skill.anysearch-skill 时真正执行搜索。
            metadata.put("executor", Map.of(
                    "type", "script",
                    "command", "node",
                    "args", List.of("scripts/anysearch_cli.js", "search", "${query}"),
                    "timeoutSeconds", 60,
                    "env", Map.of("SSLKEYLOGFILE", ""),
                    "inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "query", Map.of(
                                            "type", "string",
                                            "description", "搜索关键词，例如 Spring AI latest version")),
                            "required", List.of("query"),
                            "additionalProperties", true)));
        }
        List<String> permissions = new ArrayList<>(manifest.permissions());
        if (!permissions.contains("network")) {
            permissions.add("network");
        }
        if (!permissions.contains("script")) {
            permissions.add("script");
        }
        SkillManifest enriched = new SkillManifest(
                manifest.id(),
                manifest.name(),
                manifest.version(),
                manifest.description(),
                manifest.enabled(),
                manifest.entrypoint(),
                manifest.tools(),
                permissions,
                metadata);
        return new SkillPackage(enriched, skillPackage.content(), skillPackage.resourceFiles());
    }

    private boolean isAnySearchSkill(SkillManifest manifest, Map<String, String> resourceFiles) {
        String name = manifest.name() == null ? "" : manifest.name().toLowerCase();
        String id = manifest.id() == null ? "" : manifest.id().toLowerCase();
        return (name.contains("anysearch") || id.contains("anysearch"))
                && resourceFiles.keySet().stream().anyMatch(path -> path.replace('\\', '/').equals("scripts/anysearch_cli.js"));
    }

    private Map<String, String> downloadResourceFiles(GithubRepo repo, String skillDir, JsonNode treeItems) throws Exception {
        Map<String, String> resources = new LinkedHashMap<>();
        String prefix = skillDir.isBlank() ? "" : skillDir + "/";
        for (JsonNode item : treeItems) {
            String type = item.path("type").asText();
            String path = item.path("path").asText();
            if (!"blob".equals(type) || !path.startsWith(prefix) || path.equals(prefix + "SKILL.md")) {
                continue;
            }
            String relativePath = path.substring(prefix.length());
            if (!isSkillResource(relativePath)) {
                continue;
            }
            String rawUrl = "https://raw.githubusercontent.com/" + repo.owner + "/" + repo.name + "/" + repo.branch + "/" + path;
            // 资源文件和 SKILL.md 同目录安装，保留 scripts/references/assets 等相对路径，确保说明中的命令可直接执行。
            resources.put(relativePath, fetchText(rawUrl));
        }
        return resources;
    }

    private boolean isSkillResource(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        for (String dir : RESOURCE_DIRS) {
            if (normalized.startsWith(dir + "/")) {
                return true;
            }
        }
        return RESOURCE_FILES.contains(normalized);
    }

    private String skillDirectory(String skillMdPath) {
        int slash = skillMdPath.lastIndexOf('/');
        return slash < 0 ? "" : skillMdPath.substring(0, slash);
    }

    private String fetchText(String url) throws Exception {
        AgentHttpResponse response = AgentHttpClient.get(url, Map.of(), REQUEST_TIMEOUT_MS);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + url);
        }
        return response.body();
    }

    private boolean isGithubRepositoryUrl(String sourceUrl) {
        return sourceUrl.matches("https?://github\\.com/[^/]+/[^/#?]+(?:\\.git)?/?");
    }

    private GithubRepo parseGithubRepo(String sourceUrl) {
        String[] parts = URI.create(sourceUrl.replace(".git", "")).getPath().replaceAll("^/+", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("不是有效 GitHub 仓库 URL：" + sourceUrl);
        }
        return new GithubRepo(parts[0], parts[1]);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class GithubRepo {
        private final String owner;
        private final String name;
        private String branch = "main";

        private GithubRepo(String owner, String name) {
            this.owner = owner;
            this.name = name;
        }
    }
}
