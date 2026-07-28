package com.github.clawagent.toolkit.execute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.clawagent.core.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteCommandRiskClassifierTest {
    private final ExecuteCommandRiskClassifier classifier = new ExecuteCommandRiskClassifier();

    @TempDir
    Path tempDir;

    @Test
    void classifiesReadOnlyGitCommandsAsLowRisk() {
        CommandRiskAssessment assessment = classifier.classify("git", List.of("status", "--short"));

        assertEquals("low", assessment.riskLevel());
        assertEquals("git-query", assessment.category());
    }

    @Test
    void classifiesShellScriptsAsHighRisk() {
        CommandRiskAssessment assessment = classifier.classify("powershell", List.of("-File", "build.ps1"));

        assertEquals("high", assessment.riskLevel());
        assertTrue(assessment.approvalRequired());
    }

    @Test
    void classifiesDependencyInstallAsHighRisk() {
        CommandRiskAssessment assessment = classifier.classify("npm", List.of("install"));

        assertEquals("high", assessment.riskLevel());
        assertEquals("mutating-command", assessment.category());
    }

    @Test
    void classifiesSensitivePathQueriesAsHighRisk() {
        CommandRiskAssessment assessment = classifier.classify(
                "cmd",
                List.of("/c", "type", ".env"),
                List.of("**/.env", "**/*.pem", "**/.ssh/**", "**/.git/**"));

        assertEquals("high", assessment.riskLevel());
        assertEquals("sensitive-path", assessment.category());
        assertTrue(assessment.approvalRequired());
    }

    @Test
    void loadsSensitivePathPatternsFromEnv() {
        ExecuteToolkitProperties properties = ExecuteToolkitProperties.fromEnv(Map.of(
                "SENSITIVE_PATH_PATTERNS", "**/.env;**/*.pem"
        ));

        assertEquals(List.of("**/.env", "**/*.pem"), properties.getSensitivePathPatterns());
    }

    @Test
    void loadsWorkerIsolationConfigFromEnv() {
        ExecuteToolkitProperties properties = ExecuteToolkitProperties.fromEnv(Map.ofEntries(
                Map.entry("WORKER_ENABLED", "false"),
                Map.entry("WORKER_JAR", "build/claw-agent-worker.jar"),
                Map.entry("WORKER_JAVA", "D:/java/bin/java.exe"),
                Map.entry("WORKER_JVM_MAX_HEAP", "128m"),
                Map.entry("WORKER_MAX_OUTPUT_BYTES", "4096"),
                Map.entry("WORKER_MAX_CPU_TIME_MS", "250"),
                Map.entry("WORKER_MAX_MEMORY_BYTES", "10485760"),
                Map.entry("WORKER_MAX_CONCURRENT", "3"),
                Map.entry("WORKER_ACQUIRE_TIMEOUT_MS", "1200"),
                Map.entry("WORKER_TERMINATION_GRACE_MS", "800"),
                Map.entry("WORKER_BLOCKED_ENV_NAME_FRAGMENTS", "TOKEN;API_KEY"),
                Map.entry("MAX_TIMEOUT_MS", "90000")
        ));

        assertFalse(properties.isWorkerEnabled());
        assertEquals("build/claw-agent-worker.jar", properties.getWorkerJar());
        assertEquals("D:/java/bin/java.exe", properties.getWorkerJava());
        assertEquals("128m", properties.getWorkerJvmMaxHeap());
        assertEquals(4096, properties.getWorkerMaxOutputBytes());
        assertEquals(250, properties.getWorkerMaxCpuTimeMs());
        assertEquals(10485760, properties.getWorkerMaxMemoryBytes());
        assertEquals(3, properties.getWorkerMaxConcurrent());
        assertEquals(1200, properties.getWorkerAcquireTimeoutMs());
        assertEquals(800, properties.getWorkerTerminationGraceMs());
        assertEquals(List.of("TOKEN", "API_KEY"), properties.getWorkerBlockedEnvNameFragments());
        assertEquals(90000, properties.getMaxTimeoutMs());
    }

    @Test
    void splitsFullCommandStringBeforeExecution() {
        ExecuteCommandTool tool = new ExecuteCommandTool(new ExecuteToolkitProperties());

        ExecuteCommandTool.CommandInvocation invocation = tool.commandInvocation(new ToolCall(
                "builtin.execute.command",
                Map.of("command", "npm install lodash")
        ));

        assertEquals("npm", invocation.command());
        assertEquals(List.of("install", "lodash"), invocation.args());
    }

    @Test
    void normalizesCmdBackslashExecuteFlag() {
        ExecuteCommandTool tool = new ExecuteCommandTool(new ExecuteToolkitProperties());

        ExecuteCommandTool.CommandInvocation invocation = tool.commandInvocation(new ToolCall(
                "builtin.execute.command",
                Map.of("command", "cmd \\c npm install lodash")
        ));

        assertEquals("cmd", invocation.command());
        assertEquals(List.of("/c", "npm", "install", "lodash"), invocation.args());
    }

    @Test
    void usesConfiguredDefaultCwdWhenCwdArgumentIsMissing() {
        Path workspace = tempDir.resolve("workspace");
        ExecuteToolkitProperties properties = ExecuteToolkitProperties.fromEnv(Map.of(
                "ALLOWED_ROOTS", tempDir.toString(),
                "DEFAULT_CWD", workspace.toString()
        ));
        ExecuteCommandTool tool = new ExecuteCommandTool(properties);

        Path resolved = tool.resolveCwd(null);

        assertEquals(workspace.toAbsolutePath().normalize(), resolved);
        assertTrue(resolved.toFile().isDirectory());
    }

    @Test
    void projectCommandUsesOnlyRunnableMavenChildWhenDefaultCwdIsWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path backend = workspace.resolve("admin-system");
        Path frontend = workspace.resolve("admin-system-frontend");
        Files.createDirectories(backend.resolve("src/main/java/com/example"));
        Files.createDirectories(frontend);
        Files.writeString(backend.resolve("pom.xml"), "<project/>");
        Files.writeString(backend.resolve("src/main/java/com/example/DemoApplication.java"), "class DemoApplication {}");
        Files.writeString(frontend.resolve("package.json"), "{}");
        ExecuteToolkitProperties properties = ExecuteToolkitProperties.fromEnv(Map.of(
                "ALLOWED_ROOTS", tempDir.toString(),
                "DEFAULT_CWD", workspace.toString()
        ));
        ExecuteCommandTool tool = new ExecuteCommandTool(properties);
        ExecuteCommandTool.CommandInvocation invocation = tool.commandInvocation(new ToolCall(
                "builtin.execute.command",
                Map.of("command", "mvn spring-boot:run")
        ));

        Path resolved = tool.resolveCwd(null, invocation, null);

        assertEquals(backend.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void projectCommandRequiresConfirmationWhenMultipleRunnableChildrenMatch() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        createMavenApp(workspace.resolve("one"));
        createMavenApp(workspace.resolve("two"));
        ExecuteToolkitProperties properties = ExecuteToolkitProperties.fromEnv(Map.of(
                "ALLOWED_ROOTS", tempDir.toString(),
                "DEFAULT_CWD", workspace.toString()
        ));
        ExecuteCommandTool tool = new ExecuteCommandTool(properties);
        ExecuteCommandTool.CommandInvocation invocation = tool.commandInvocation(new ToolCall(
                "builtin.execute.command",
                Map.of("command", "mvn spring-boot:run")
        ));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tool.resolveCwd(null, invocation, null));

        assertTrue(error.getMessage().contains("发现多个候选项目"));
    }

    private void createMavenApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("src/main/java/com/example/DemoApplication.java"), "class DemoApplication {}");
    }

}
