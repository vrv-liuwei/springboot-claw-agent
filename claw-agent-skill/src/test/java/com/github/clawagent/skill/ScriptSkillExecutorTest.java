package com.github.clawagent.skill;

import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSkillExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void scriptExecutorUsesInjectedProcessExecutor() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<File> cwd = new AtomicReference<>();
        AtomicReference<Map<String, String>> env = new AtomicReference<>();
        SkillProcessExecutor processExecutor = (commandLine, workingDirectory, environment, timeoutMs) -> {
            command.set(commandLine);
            cwd.set(workingDirectory);
            env.set(environment);
            return new SkillProcessExecutor.Result(
                    0,
                    false,
                    12,
                    "script ok",
                    "",
                    true,
                    false,
                    false,
                    false,
                    "",
                    0,
                    0,
                    3,
                    1);
        };
        SkillManifest manifest = new SkillManifest(
                "demo-script",
                "Demo Script",
                "0.1.0",
                "测试脚本 Skill",
                true,
                "SKILL.md",
                List.of("default"),
                List.of("shell"),
                Map.of("executor", Map.of(
                        "type", "script",
                        "command", "node",
                        "args", List.of("${arg.script}"),
                        "cwd", ".",
                        "env", Map.of("CLAW_TEST_ENV", "${arg.value}"))));
        SkillAgentTool tool = new SkillAgentTool(manifest, "default", "skill.demo-script", tempDir, processExecutor);

        ToolResult result = tool.execute(new ToolCall("skill.demo-script", Map.of(
                "script", "runner.js",
                "value", "env-value")), null);

        assertTrue(result.success(), result.content());
        assertEquals(List.of("node", "runner.js"), command.get());
        assertEquals(tempDir.normalize().toFile(), cwd.get());
        assertEquals(Map.of("CLAW_TEST_ENV", "env-value"), env.get());
        assertTrue(result.content().contains("workerIsolated: true"));
        assertTrue(result.content().contains("workerPoolWaitMs: 3"));
        assertTrue(result.content().contains("workerEnvBlockedCount: 1"));
        assertTrue(result.content().contains("script ok"));
    }
}
