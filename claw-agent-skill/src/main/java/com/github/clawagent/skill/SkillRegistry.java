package com.github.clawagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 注册表。
 * Runtime 和 WebUI 都只依赖这个接口，具体是文件、数据库还是远端仓库由实现决定。
 */
public interface SkillRegistry {
    SkillRegistration install(SkillPackage skillPackage);

    SkillRegistration enable(String skillId);

    SkillRegistration disable(String skillId);

    Optional<SkillRegistration> find(String skillId);

    List<SkillRegistration> list();
}
