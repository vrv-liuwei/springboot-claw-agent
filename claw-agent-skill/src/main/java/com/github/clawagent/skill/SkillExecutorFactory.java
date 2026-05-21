package com.github.clawagent.skill;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 执行器工厂。
 * 根据 manifest.metadata 中的 executor 配置选择具体执行器。
 */
final class SkillExecutorFactory {
    private final SkillExecutor documentExecutor = new DocumentSkillExecutor();
    private final SkillExecutor httpExecutor = new HttpSkillExecutor();
    private final SkillExecutor scriptExecutor = new ScriptSkillExecutor();
    private final SkillExecutor javaExecutor = new JavaSkillExecutor();

    SkillExecutor executorFor(Map<String, Object> config) {
        String type = stringValue(config, "type", "document").trim().toLowerCase();
        return switch (type) {
            case "document", "doc", "markdown" -> documentExecutor;
            case "http", "https", "rest" -> httpExecutor;
            case "script", "process", "command" -> scriptExecutor;
            case "java", "plugin", "class" -> javaExecutor;
            default -> throw new IllegalArgumentException("不支持的 Skill executor type：" + type);
        };
    }

    Map<String, Object> configFor(SkillManifest manifest, String toolName) {
        Map<String, Object> metadata = manifest.metadata();
        Object tools = metadata.get("tools");
        if (tools instanceof Map<?, ?> toolMap) {
            // 优先读取 metadata.tools.<toolName>，允许一个 Skill 暴露多个不同执行器。
            Object specific = toolMap.get(toolName);
            if (specific instanceof Map<?, ?> specificMap) {
                return toStringKeyMap(specificMap);
            }
        }
        Object executor = metadata.get("executor");
        if (executor instanceof Map<?, ?> executorMap) {
            return toStringKeyMap(executorMap);
        }
        return Map.of("type", "document");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStringKeyMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String stringValue(Map<String, Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
