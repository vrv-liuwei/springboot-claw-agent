package com.github.clawagent.skill;

import java.time.Instant;

/**
 * Skill 注册状态。
 * installedPath 明确告诉管理台该 Skill 最终保存到了本机哪个目录。
 */
public record SkillRegistration(
        /** Skill 的 manifest 元数据。 */
        SkillManifest manifest,
        /** 本次加载或安装的时间。 */
        Instant installedAt,
        /** Skill 在本机文件系统中的安装目录。 */
        String installedPath,
        /** 注册状态，例如 INSTALLED、ENABLED、DISABLED。 */
        String status,
        /** 状态补充说明，例如 loaded、installed、disabled。 */
        String message) {
}
