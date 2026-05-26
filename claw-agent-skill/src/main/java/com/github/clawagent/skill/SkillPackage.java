package com.github.clawagent.skill;

import java.util.Map;

/**
 * WebUI/API 安装 Skill 时提交的包。
 * content 用于保存 README、提示词或脚本说明，二进制包上传会在后续版本扩展。
 */
public record SkillPackage(
        /** Skill 安装所需的 manifest 元数据。 */
        SkillManifest manifest,
        /** Skill 入口内容，当前默认写入 README.md 或 manifest.entrypoint 指定文件。 */
        String content,
        /** Skill 随包资源文件，key 为相对路径，例如 scripts/install.py。 */
        Map<String, String> resourceFiles) {

    public SkillPackage(SkillManifest manifest, String content) {
        this(manifest, content, Map.of());
    }

    public SkillPackage {
        resourceFiles = resourceFiles == null ? Map.of() : Map.copyOf(resourceFiles);
    }
}
