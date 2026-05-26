package com.github.clawagent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 Skill 转换器。
 * Codex 和 Claude 生态通常只提供带 YAML frontmatter 的 SKILL.md；
 * ClawAgent 运行时需要 manifest.json，因此安装前统一转换。
 */
public final class SkillPackageConverter {
    private SkillPackageConverter() {
    }

    public static SkillPackage fromSkillMarkdown(String content, String idOverride, String nameOverride,
                                                 String descriptionOverride, boolean enabled,
                                                 Map<String, Object> metadata) {
        Frontmatter frontmatter = parseFrontmatter(content);
        String name = firstNonBlank(nameOverride, frontmatter.value("name"), idOverride, "imported-skill");
        String id = firstNonBlank(idOverride, slug(name));
        String description = firstNonBlank(descriptionOverride, frontmatter.value("description"), "Imported external skill: " + name);
        Map<String, Object> mergedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        mergedMetadata.putIfAbsent("sourceFormat", "codex-or-claude-skill");
        SkillManifest manifest = new SkillManifest(
                id,
                name,
                "0.1.0",
                description,
                enabled,
                "SKILL.md",
                List.of("default"),
                List.of(),
                mergedMetadata);
        return new SkillPackage(manifest, content);
    }

    public static SkillPackage fromSkillMarkdown(String content, String idOverride, String nameOverride,
                                                 String descriptionOverride, boolean enabled,
                                                 Map<String, Object> metadata,
                                                 Map<String, String> resourceFiles) {
        SkillPackage skillPackage = fromSkillMarkdown(content, idOverride, nameOverride, descriptionOverride, enabled, metadata);
        return new SkillPackage(skillPackage.manifest(), skillPackage.content(), resourceFiles);
    }

    private static Frontmatter parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return new Frontmatter(Map.of());
        }
        String[] lines = content.split("\\R", -1);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.trim())) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                values.put(key, trimQuotes(value));
            }
        }
        return new Frontmatter(values);
    }

    static String slug(String value) {
        String normalized = value == null ? "" : value.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "imported-skill" : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trimQuotes(String value) {
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record Frontmatter(Map<String, String> values) {
        String value(String key) {
            return values.get(key);
        }
    }
}
