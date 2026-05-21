package com.github.clawagent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 的声明文件模型。
 * 当前阶段先持久化元数据、权限和入口信息，后续工具加载器会基于这些字段创建 AgentTool。
 */
public record SkillManifest(
        /** Skill 唯一标识，也是本地目录名和 toolId 的一部分。 */
        String id,
        /** Skill 展示名称。 */
        String name,
        /** Skill 版本号，用于后续升级和兼容判断。 */
        String version,
        /** Skill 能力描述，用于管理台展示和 LLM 选择工具。 */
        String description,
        /** 是否启用该 Skill；禁用后不会注册对应工具。 */
        boolean enabled,
        /** Skill 入口文件，例如 SKILL.md 或 README.md。 */
        String entrypoint,
        /** Skill 暴露出的工具名列表；为空时默认注册 default 工具。 */
        List<String> tools,
        /** Skill 需要的权限声明，例如 file、network、shell。 */
        List<String> permissions,
        /** 扩展元数据，预留给 UI、来源仓库、标签等非核心字段。 */
        Map<String, Object> metadata) {
    public SkillManifest {
        // 对集合字段做不可变防御拷贝，避免安装后被调用方继续修改。
        tools = tools == null ? List.of() : List.copyOf(tools);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
