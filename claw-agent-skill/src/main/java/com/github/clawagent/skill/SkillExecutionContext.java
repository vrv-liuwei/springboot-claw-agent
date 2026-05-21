package com.github.clawagent.skill;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 单次执行上下文。
 * 聚合 manifest、工具名、安装目录和 executor 配置，避免 executor 直接依赖注册表。
 */
record SkillExecutionContext(
        /** 当前 Skill manifest。 */
        SkillManifest manifest,
        /** 当前被调用的 Skill tool 名称。 */
        String toolName,
        /** Skill 安装目录。 */
        Path skillDir,
        /** 当前 tool 的 executor 配置。 */
        Map<String, Object> executorConfig) {
    SkillExecutionContext {
        executorConfig = executorConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(executorConfig);
    }
}
