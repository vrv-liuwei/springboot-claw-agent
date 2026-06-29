package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.skill.SkillManifest;
import com.github.clawagent.skill.SkillPackage;
import com.github.clawagent.skill.SkillRegistration;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.spi.AgentEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillControllerTest {
    @Test
    void enableSkillWritesGlobalAuditEvent() {
        RecordingEventStore eventStore = new RecordingEventStore();
        SkillController controller = new SkillController(new FakeSkillRegistry(), eventStore);

        SkillRegistration registration = controller.enableSkill("demo-skill");

        assertEquals(true, registration.manifest().enabled());
        assertEquals("skill.enabled", eventStore.events.get(0).type());
        assertEquals("demo-skill", eventStore.events.get(0).details().get("skillId"));
        assertEquals("true", eventStore.events.get(0).details().get("enabled"));
    }

    @Test
    void disableSkillWritesGlobalAuditEvent() {
        RecordingEventStore eventStore = new RecordingEventStore();
        SkillController controller = new SkillController(new FakeSkillRegistry(), eventStore);

        SkillRegistration registration = controller.disableSkill("demo-skill");

        assertEquals(false, registration.manifest().enabled());
        assertEquals("skill.disabled", eventStore.events.get(0).type());
        assertEquals("demo-skill", eventStore.events.get(0).details().get("skillId"));
        assertEquals("false", eventStore.events.get(0).details().get("enabled"));
    }

    private static SkillRegistration registration(boolean enabled) {
        SkillManifest manifest = new SkillManifest(
                "demo-skill",
                "Demo Skill",
                "1.0.0",
                "用于验证 Skill 管理审计",
                enabled,
                "SKILL.md",
                List.of("default"),
                List.of("file"),
                Map.of("source", "test"));
        return new SkillRegistration(manifest, Instant.parse("2026-06-01T00:00:00Z"),
                "D:/workspace/.clawagent/skills/demo-skill", enabled ? "ENABLED" : "DISABLED", "test");
    }

    private static final class FakeSkillRegistry implements SkillRegistry {
        @Override
        public SkillRegistration install(SkillPackage skillPackage) {
            return registration(skillPackage.manifest().enabled());
        }

        @Override
        public SkillRegistration update(String skillId, SkillPackage skillPackage) {
            return registration(skillPackage.manifest().enabled());
        }

        @Override
        public SkillRegistration enable(String skillId) {
            // 测试只关心 Controller 是否把启用结果写入审计，不需要触发真实目录同步。
            return registration(true);
        }

        @Override
        public SkillRegistration disable(String skillId) {
            // 禁用路径同样用内存 registration 验证，避免单测依赖本地 Skill 目录。
            return registration(false);
        }

        @Override
        public boolean delete(String skillId) {
            return true;
        }

        @Override
        public Optional<SkillRegistration> find(String skillId) {
            return Optional.of(registration(true));
        }

        @Override
        public List<SkillRegistration> list() {
            return List.of(registration(true));
        }
    }

    private static final class RecordingEventStore implements AgentEventStore {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void saveEvent(AgentEvent event) {
            events.add(event);
        }

        @Override
        public List<AgentEvent> findEventsBySession(String sessionId, int limit) {
            return events;
        }

        @Override
        public List<AgentEvent> findEventsByTask(String taskId, int limit) {
            return events;
        }

        @Override
        public List<AgentEvent> findEvents(Instant from, Instant to, String level, String type,
                                           String sessionId, String taskId, int limit) {
            return events;
        }
    }
}
