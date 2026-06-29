package com.github.clawagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 注册表。
 * Runtime 和 WebUI 都只依赖这个接口，具体是文件、数据库还是远端仓库由实现决定。
 */
public interface SkillRegistry {
    /**
     * 安装或覆盖一个 Skill 包，并根据 manifest.enabled 动态注册工具。
     */
    SkillRegistration install(SkillPackage skillPackage);

    /**
     * 更新已存在的 Skill 包，并根据 manifest.enabled 重新同步工具表。
     */
    SkillRegistration update(String skillId, SkillPackage skillPackage);

    /**
     * 启用指定 Skill，启用后会把 manifest 声明的工具重新注册到运行时工具表。
     */
    SkillRegistration enable(String skillId);

    /**
     * 禁用指定 Skill，禁用后会从运行时工具表移除对应 skill.* 工具。
     */
    SkillRegistration disable(String skillId);

    /**
     * 删除指定 Skill，并卸载它注册到运行时的 skill.* 工具。
     */
    boolean delete(String skillId);

    /**
     * 按 Skill ID 查询当前 JVM 已加载的注册信息。
     */
    Optional<SkillRegistration> find(String skillId);

    /**
     * 查询当前 JVM 已加载的所有 Skill。
     */
    List<SkillRegistration> list();

    /**
     * 从本地 Skill 目录重新扫描 manifest 和资源，支持后台维护后动态加载。
     */
    default List<SkillRegistration> refresh() {
        return list();
    }
}
