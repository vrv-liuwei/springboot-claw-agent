package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java 插件型 Skill 执行器。
 * 用于把企业内部已经封装好的 Java 能力作为 Skill tool 暴露出来。
 */
class JavaSkillExecutor implements SkillExecutor {
    @Override
    public ToolResult execute(SkillExecutionContext context, ToolCall call, AgentContext agentContext) {
        requireJavaPermission(context);
        String className = required(context.executorConfig(), "className");
        try {
            Class<?> pluginClass = loadClass(context.skillDir(), context.executorConfig(), className);
            Object instance = pluginClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof SkillJavaPlugin plugin)) {
                return ToolResult.error("Java Skill 插件未实现 SkillJavaPlugin：" + className);
            }
            // 插件可以返回结构化 ToolResult；旧插件会通过接口默认方法转换成成功文本。
            return plugin.executeTool(call, agentContext, context.executorConfig());
        } catch (Exception e) {
            return ToolResult.error("Java Skill 执行失败：" + e.getMessage());
        }
    }

    private Class<?> loadClass(Path skillDir, Map<String, Object> config, String className) throws Exception {
        List<URL> urls = new ArrayList<>();
        Path normalizedSkillDir = skillDir.normalize().toAbsolutePath();
        Path libDir = normalizedSkillDir.resolve("lib").normalize();
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.list(libDir)) {
                for (Path jar : stream.filter(path -> path.toString().endsWith(".jar")).toList()) {
                    urls.add(validateJar(normalizedSkillDir, jar).toUri().toURL());
                }
            }
        }
        Object jars = config.get("jars");
        if (jars instanceof List<?> jarList) {
            for (Object jar : jarList) {
                // jars 允许显式指定 Skill 目录内的 jar，便于一个 Skill 管理多个插件包。
                Path jarPath = normalizedSkillDir.resolve(String.valueOf(jar)).normalize();
                urls.add(validateJar(normalizedSkillDir, jarPath).toUri().toURL());
            }
        }
        if (urls.isEmpty()) {
            return Class.forName(className);
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), Thread.currentThread().getContextClassLoader());
        return Class.forName(className, true, loader);
    }

    private Path validateJar(Path skillDir, Path jar) {
        Path normalized = jar.normalize().toAbsolutePath();
        // Java 插件 Jar 必须放在当前 Skill 目录内，避免通过 manifest 加载任意本地文件。
        if (!normalized.startsWith(skillDir)) {
            throw new IllegalArgumentException("Java Skill lib 越权：" + jar);
        }
        if (!normalized.toString().endsWith(".jar") || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Java Skill lib 不是有效 jar：" + jar);
        }
        return normalized;
    }

    private void requireJavaPermission(SkillExecutionContext context) {
        boolean allowed = context.manifest().permissions().stream()
                .map(item -> item == null ? "" : item.toLowerCase())
                .anyMatch(item -> item.contains("java") || item.contains("plugin"));
        if (!allowed) {
            throw new IllegalStateException("Skill 未声明 java/plugin 权限，不能执行 java executor");
        }
    }

    private String required(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Java Skill 缺少 executor." + key);
        }
        return String.valueOf(value);
    }
}
