package com.github.clawagent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSkillRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void updateRequiresExistingSkillAndKeepsOriginalId() throws Exception {
        FileSkillRegistry registry = new FileSkillRegistry(tempDir);
        SkillRegistration installed = registry.install(new SkillPackage(
                manifest("demo-skill", "Demo Skill", true),
                "# Demo Skill"));
        Instant installedAt = installed.installedAt();

        SkillRegistration updated = registry.update("demo-skill", new SkillPackage(
                manifest("other-id", "Updated Skill", false),
                "# Updated Skill",
                Map.of("references/guide.md", "updated guide")));

        assertEquals("demo-skill", updated.manifest().id());
        assertEquals("Updated Skill", updated.manifest().name());
        assertEquals("DISABLED", updated.status());
        assertEquals(installedAt, updated.installedAt());
        assertEquals("# Updated Skill", Files.readString(tempDir.resolve("demo-skill").resolve("SKILL.md")));
        assertEquals("updated guide", Files.readString(tempDir.resolve("demo-skill").resolve("references").resolve("guide.md")));
    }

    @Test
    void updateRejectsMissingSkill() {
        FileSkillRegistry registry = new FileSkillRegistry(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
                registry.update("missing", new SkillPackage(manifest("missing", "Missing", true), "# Missing")));
    }

    private SkillManifest manifest(String id, String name, boolean enabled) {
        return new SkillManifest(
                id,
                name,
                "0.1.0",
                "用于测试 Skill 更新",
                enabled,
                "SKILL.md",
                List.of("default"),
                List.of("file"),
                Map.of("source", "test"));
    }
}
