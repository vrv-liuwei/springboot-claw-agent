package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.skill.ExternalSkillInstallTool;
import com.github.clawagent.skill.SkillPackage;
import com.github.clawagent.skill.SkillRegistration;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.spi.AgentEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skill 管理接口。
 * Skill 的目录解析、manifest 校验和启停状态仍由 SkillRegistry 负责，Controller 只处理 HTTP 入参。
 */
@RestController
@RequestMapping("/api/v1")
public class SkillController {
    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry skillRegistry;
    private final AgentEventStore eventStore;

    public SkillController(SkillRegistry skillRegistry,
                           @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.skillRegistry = skillRegistry;
        this.eventStore = eventStore;
    }

    @PostMapping("/skills")
    public SkillRegistration installSkill(@RequestBody SkillPackage skillPackage) {
        log.info("skill install received id={} name={}",
                skillPackage.manifest() == null ? null : skillPackage.manifest().id(),
                skillPackage.manifest() == null ? null : skillPackage.manifest().name());
        SkillRegistration registration = skillRegistry.install(skillPackage);
        recordSkillAudit("skill.installed", "Skill 已安装", registration,
                Map.of("enabled", String.valueOf(registration.manifest().enabled())));
        return registration;
    }

    @GetMapping("/skills")
    public List<SkillRegistration> skills() {
        return skillRegistry.list();
    }

    @PostMapping("/skills/refresh")
    public List<SkillRegistration> refreshSkills() {
        log.info("skill refresh requested");
        return skillRegistry.refresh();
    }

    @PostMapping("/skills/import")
    public List<SkillRegistration> importSkill(@RequestBody SkillImportRequest request) {
        log.info("skill import requested sourceUrl={} id={}", request.sourceUrl(), request.id());
        try {
            Map<String, String> arguments = new LinkedHashMap<>();
            putIfPresent(arguments, "sourceUrl", request.sourceUrl());
            putIfPresent(arguments, "skillMd", request.skillMd());
            putIfPresent(arguments, "id", request.id());
            putIfPresent(arguments, "name", request.name());
            putIfPresent(arguments, "description", request.description());
            arguments.put("overwrite", String.valueOf(Boolean.TRUE.equals(request.overwrite())));
            // 复用 Agent 内部的外部 Skill 安装逻辑，后台导入和模型工具调用保持同一套目录规则。
            List<SkillRegistration> registrations = new ExternalSkillInstallTool(skillRegistry).installFromArguments(arguments);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("source", request.sourceUrl() == null || request.sourceUrl().isBlank() ? "inline" : "url");
            details.put("count", String.valueOf(registrations.size()));
            recordSkillAudit("skill.imported", "Skill 已导入", request.id(), request.name(), details);
            return registrations;
        } catch (Exception e) {
            throw new IllegalArgumentException("Skill 导入失败：" + e.getMessage(), e);
        }
    }

    @GetMapping("/skills/{skillId}")
    public SkillRegistration skill(@PathVariable("skillId") String skillId) {
        return skillRegistry.find(skillId).orElseThrow(() -> new IllegalArgumentException("Skill 不存在：" + skillId));
    }

    @PutMapping("/skills/{skillId}")
    public SkillRegistration updateSkill(
            @PathVariable("skillId") String skillId,
            @RequestBody SkillPackage skillPackage) {
        log.info("skill update requested id={}", skillId);
        SkillRegistration registration = skillRegistry.update(skillId, skillPackage);
        recordSkillAudit("skill.updated", "Skill 已更新", registration,
                Map.of("enabled", String.valueOf(registration.manifest().enabled())));
        return registration;
    }

    @PostMapping("/skills/{skillId}/enable")
    public SkillRegistration enableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill enable requested id={}", skillId);
        SkillRegistration registration = skillRegistry.enable(skillId);
        // 启停会改变运行时可用工具集合，必须进入全局审计，便于回溯某个 skill.* 工具为什么出现或消失。
        recordSkillAudit("skill.enabled", "Skill 已启用", registration,
                Map.of("enabled", String.valueOf(registration.manifest().enabled())));
        return registration;
    }

    @PostMapping("/skills/{skillId}/disable")
    public SkillRegistration disableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill disable requested id={}", skillId);
        SkillRegistration registration = skillRegistry.disable(skillId);
        // 禁用同样属于工具面变更，不记录完整 manifest，只落 skillId/name/enabled 等安全摘要。
        recordSkillAudit("skill.disabled", "Skill 已禁用", registration,
                Map.of("enabled", String.valueOf(registration.manifest().enabled())));
        return registration;
    }

    @DeleteMapping("/skills/{skillId}")
    public Map<String, Object> deleteSkill(@PathVariable("skillId") String skillId) {
        log.warn("skill delete requested id={}", skillId);
        boolean deleted = skillRegistry.delete(skillId);
        recordSkillAudit("skill.deleted", deleted ? "Skill 已删除" : "Skill 删除未命中", skillId, null,
                Map.of("deleted", String.valueOf(deleted)));
        return Map.of("deleted", deleted, "skillId", skillId);
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            // 外部 Skill 安装工具按字符串参数工作，这里只传有效字段，避免空字符串覆盖 frontmatter。
            target.put(key, value.trim());
        }
    }

    private void recordSkillAudit(String type, String message, String skillId, String name, Map<String, String> extra) {
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "skillId", skillId);
        putIfPresent(details, "name", name);
        if (extra != null) {
            details.putAll(extra);
        }
        // Skill 管理动作属于全局配置变更，不绑定具体会话或任务。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void recordSkillAudit(String type, String message, SkillRegistration registration,
                                  Map<String, String> extra) {
        recordSkillAudit(type, message, registration.manifest().id(), registration.manifest().name(), extra);
    }

    /**
     * 后台导入外部 Skill 的请求。
     *
     * @param sourceUrl GitHub 仓库 URL、raw SKILL.md URL 或普通 SKILL.md URL。
     * @param skillMd SKILL.md 原文；用户直接粘贴内容时使用。
     * @param id 可选 Skill ID 覆盖值。
     * @param name 可选 Skill 名称覆盖值。
     * @param description 可选 Skill 描述覆盖值。
     * @param overwrite 是否覆盖同名 Skill；默认不覆盖，避免误删用户本地修改。
     */
    public record SkillImportRequest(
            String sourceUrl,
            String skillMd,
            String id,
            String name,
            String description,
            Boolean overwrite
    ) {
    }
}
