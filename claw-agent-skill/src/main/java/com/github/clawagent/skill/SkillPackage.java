package com.github.clawagent.skill;

import java.util.Map;

/**
 * WebUI/API 安装 Skill 时提交的包。
 * content 用于保存 SKILL.md，resourceFiles 和 binaryResourceFiles 用于保留 scripts/assets/references/lib 等随包资源。
 *
 * @param manifest Skill 安装所需的 manifest 元数据。
 * @param content Skill 入口内容，当前默认写入 README.md 或 manifest.entrypoint 指定文件。
 * @param resourceFiles Skill 随包文本资源文件，key 为相对路径，例如 scripts/install.py。
 * @param binaryResourceFiles Skill 随包二进制资源文件，key 为相对路径，例如 lib/plugin.jar。
 */
public record SkillPackage(
        SkillManifest manifest,
        String content,
        Map<String, String> resourceFiles,
        Map<String, byte[]> binaryResourceFiles) {

    public SkillPackage(SkillManifest manifest, String content) {
        this(manifest, content, Map.of(), Map.of());
    }

    public SkillPackage(SkillManifest manifest, String content, Map<String, String> resourceFiles) {
        this(manifest, content, resourceFiles, Map.of());
    }

    public SkillPackage {
        resourceFiles = resourceFiles == null ? Map.of() : Map.copyOf(resourceFiles);
        binaryResourceFiles = binaryResourceFiles == null ? Map.of() : Map.copyOf(binaryResourceFiles);
    }
}
