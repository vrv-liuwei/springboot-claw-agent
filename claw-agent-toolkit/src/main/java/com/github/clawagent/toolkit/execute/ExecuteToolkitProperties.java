package com.github.clawagent.toolkit.execute;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 本机命令执行工具配置。
 * execute 是高危能力，默认由 application.yml 禁用，并且运行时还会经过 ToolExecutionGuard。
 */
public class ExecuteToolkitProperties {
    private List<String> allowedRoots = new ArrayList<>(List.of("."));
    private String defaultCwd = ".";
    private long timeoutMs = 30000;
    private long maxTimeoutMs = 120000;
    private long processWaitMs = 5000;
    private int maxOutputChars = 20000;
    private boolean workerEnabled = true;
    private String workerJar = "claw-agent-worker/target/claw-agent-worker-1.0.0-SNAPSHOT.jar";
    private String workerJava = "";
    private String workerJvmMaxHeap = "256m";
    private int workerMaxOutputBytes = 1024 * 1024;
    private long workerMaxCpuTimeMs = 0;
    private long workerMaxMemoryBytes = 0;
    private int workerMaxConcurrent = 2;
    private long workerAcquireTimeoutMs = 5000;
    private long workerTerminationGraceMs = 1500;
    private boolean workerSandboxEnabled = true;
    private String workerSandboxRoot = ".clawagent/worker-sandbox";
    private boolean workerKeepSandbox = false;
    private List<String> workerAllowedEnvNames = new ArrayList<>(List.of(
            "PATH",
            "PATHEXT",
            "JAVA_HOME",
            "SystemRoot",
            "ComSpec",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "HOME",
            "APPDATA",
            "LOCALAPPDATA",
            "ProgramData",
            "M2_HOME",
            "MAVEN_OPTS",
            "GRADLE_USER_HOME"
    ));
    private List<String> workerBlockedEnvNameFragments = new ArrayList<>(List.of(
            "TOKEN",
            "SECRET",
            "PASSWORD",
            "PASSWD",
            "API_KEY",
            "ACCESS_KEY",
            "PRIVATE_KEY",
            "CREDENTIAL",
            "AUTHORIZATION",
            "COOKIE"
    ));
    private List<String> sensitivePathPatterns = new ArrayList<>(List.of(
            "**/.env",
            ".env",
            "**/.env.*",
            ".env.*",
            "**/*.key",
            "**/*.pem",
            "**/*.p12",
            "**/*.pfx",
            "**/.ssh/**",
            ".ssh/**",
            "**/.git/**",
            ".git/**"
    ));

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots == null ? new ArrayList<>() : new ArrayList<>(allowedRoots);
    }

    public String getDefaultCwd() {
        return defaultCwd;
    }

    public void setDefaultCwd(String defaultCwd) {
        this.defaultCwd = defaultCwd == null || defaultCwd.isBlank() ? "." : defaultCwd.trim();
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs <= 0 ? 30000 : timeoutMs;
    }

    public long getMaxTimeoutMs() {
        return maxTimeoutMs;
    }

    public void setMaxTimeoutMs(long maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs <= 0 ? 120000 : maxTimeoutMs;
    }

    public long getProcessWaitMs() {
        return processWaitMs;
    }

    public void setProcessWaitMs(long processWaitMs) {
        this.processWaitMs = processWaitMs <= 0 ? 5000 : processWaitMs;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public String getWorkerJar() {
        return workerJar;
    }

    public void setWorkerJar(String workerJar) {
        this.workerJar = workerJar == null || workerJar.isBlank()
                ? "claw-agent-worker/target/claw-agent-worker-1.0.0-SNAPSHOT.jar"
                : workerJar.trim();
    }

    public String getWorkerJava() {
        return workerJava;
    }

    public void setWorkerJava(String workerJava) {
        this.workerJava = workerJava == null ? "" : workerJava.trim();
    }

    public String getWorkerJvmMaxHeap() {
        return workerJvmMaxHeap;
    }

    public void setWorkerJvmMaxHeap(String workerJvmMaxHeap) {
        this.workerJvmMaxHeap = workerJvmMaxHeap == null || workerJvmMaxHeap.isBlank() ? "256m" : workerJvmMaxHeap.trim();
    }

    public int getWorkerMaxOutputBytes() {
        return workerMaxOutputBytes;
    }

    public void setWorkerMaxOutputBytes(int workerMaxOutputBytes) {
        this.workerMaxOutputBytes = workerMaxOutputBytes <= 0 ? 1024 * 1024 : workerMaxOutputBytes;
    }

    public long getWorkerMaxCpuTimeMs() {
        return workerMaxCpuTimeMs;
    }

    public void setWorkerMaxCpuTimeMs(long workerMaxCpuTimeMs) {
        this.workerMaxCpuTimeMs = Math.max(0, workerMaxCpuTimeMs);
    }

    public long getWorkerMaxMemoryBytes() {
        return workerMaxMemoryBytes;
    }

    public void setWorkerMaxMemoryBytes(long workerMaxMemoryBytes) {
        this.workerMaxMemoryBytes = Math.max(0, workerMaxMemoryBytes);
    }

    public int getWorkerMaxConcurrent() {
        return workerMaxConcurrent;
    }

    public void setWorkerMaxConcurrent(int workerMaxConcurrent) {
        this.workerMaxConcurrent = workerMaxConcurrent <= 0 ? 1 : workerMaxConcurrent;
    }

    public long getWorkerAcquireTimeoutMs() {
        return workerAcquireTimeoutMs;
    }

    public void setWorkerAcquireTimeoutMs(long workerAcquireTimeoutMs) {
        this.workerAcquireTimeoutMs = workerAcquireTimeoutMs <= 0 ? 5000 : workerAcquireTimeoutMs;
    }

    public long getWorkerTerminationGraceMs() {
        return workerTerminationGraceMs;
    }

    public void setWorkerTerminationGraceMs(long workerTerminationGraceMs) {
        this.workerTerminationGraceMs = workerTerminationGraceMs <= 0 ? 1500 : workerTerminationGraceMs;
    }

    public boolean isWorkerSandboxEnabled() {
        return workerSandboxEnabled;
    }

    public void setWorkerSandboxEnabled(boolean workerSandboxEnabled) {
        this.workerSandboxEnabled = workerSandboxEnabled;
    }

    public String getWorkerSandboxRoot() {
        return workerSandboxRoot;
    }

    public void setWorkerSandboxRoot(String workerSandboxRoot) {
        this.workerSandboxRoot = workerSandboxRoot == null || workerSandboxRoot.isBlank()
                ? ".clawagent/worker-sandbox"
                : workerSandboxRoot.trim();
    }

    public boolean isWorkerKeepSandbox() {
        return workerKeepSandbox;
    }

    public void setWorkerKeepSandbox(boolean workerKeepSandbox) {
        this.workerKeepSandbox = workerKeepSandbox;
    }

    public List<String> getWorkerAllowedEnvNames() {
        return workerAllowedEnvNames;
    }

    public void setWorkerAllowedEnvNames(List<String> workerAllowedEnvNames) {
        this.workerAllowedEnvNames = workerAllowedEnvNames == null
                ? new ArrayList<>()
                : new ArrayList<>(workerAllowedEnvNames);
    }

    public List<String> getWorkerBlockedEnvNameFragments() {
        return workerBlockedEnvNameFragments;
    }

    public void setWorkerBlockedEnvNameFragments(List<String> workerBlockedEnvNameFragments) {
        this.workerBlockedEnvNameFragments = workerBlockedEnvNameFragments == null
                ? new ArrayList<>()
                : new ArrayList<>(workerBlockedEnvNameFragments);
    }

    public List<String> getSensitivePathPatterns() {
        return sensitivePathPatterns;
    }

    public void setSensitivePathPatterns(List<String> sensitivePathPatterns) {
        this.sensitivePathPatterns = sensitivePathPatterns == null ? new ArrayList<>() : new ArrayList<>(sensitivePathPatterns);
    }

    public List<Path> allowedRootPaths() {
        return allowedRoots.stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    public static ExecuteToolkitProperties fromEnv(Map<String, String> env) {
        ExecuteToolkitProperties properties = new ExecuteToolkitProperties();
        if (env == null || env.isEmpty()) {
            return properties;
        }
        Map<String, String> normalized = env.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_'),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));
        properties.setAllowedRoots(listValue(normalized.get("ALLOWED_ROOTS"), properties.getAllowedRoots()));
        properties.setDefaultCwd(stringValue(normalized.get("DEFAULT_CWD"), properties.getDefaultCwd()));
        properties.setTimeoutMs(longValue(normalized.get("TIMEOUT_MS"), properties.getTimeoutMs()));
        properties.setMaxTimeoutMs(longValue(normalized.get("MAX_TIMEOUT_MS"), properties.getMaxTimeoutMs()));
        properties.setProcessWaitMs(longValue(normalized.get("PROCESS_WAIT_MS"), properties.getProcessWaitMs()));
        properties.setMaxOutputChars(intValue(normalized.get("MAX_OUTPUT_CHARS"), properties.getMaxOutputChars()));
        properties.setWorkerEnabled(booleanValue(normalized.get("WORKER_ENABLED"), properties.isWorkerEnabled()));
        properties.setWorkerJar(stringValue(normalized.get("WORKER_JAR"), properties.getWorkerJar()));
        properties.setWorkerJava(stringValue(normalized.get("WORKER_JAVA"), properties.getWorkerJava()));
        properties.setWorkerJvmMaxHeap(stringValue(normalized.get("WORKER_JVM_MAX_HEAP"), properties.getWorkerJvmMaxHeap()));
        properties.setWorkerMaxOutputBytes(intValue(normalized.get("WORKER_MAX_OUTPUT_BYTES"), properties.getWorkerMaxOutputBytes()));
        properties.setWorkerMaxCpuTimeMs(longValue(normalized.get("WORKER_MAX_CPU_TIME_MS"), properties.getWorkerMaxCpuTimeMs()));
        properties.setWorkerMaxMemoryBytes(longValue(normalized.get("WORKER_MAX_MEMORY_BYTES"), properties.getWorkerMaxMemoryBytes()));
        properties.setWorkerMaxConcurrent(intValue(normalized.get("WORKER_MAX_CONCURRENT"), properties.getWorkerMaxConcurrent()));
        properties.setWorkerAcquireTimeoutMs(longValue(normalized.get("WORKER_ACQUIRE_TIMEOUT_MS"), properties.getWorkerAcquireTimeoutMs()));
        properties.setWorkerTerminationGraceMs(longValue(normalized.get("WORKER_TERMINATION_GRACE_MS"), properties.getWorkerTerminationGraceMs()));
        properties.setWorkerSandboxEnabled(booleanValue(normalized.get("WORKER_SANDBOX_ENABLED"), properties.isWorkerSandboxEnabled()));
        properties.setWorkerSandboxRoot(stringValue(normalized.get("WORKER_SANDBOX_ROOT"), properties.getWorkerSandboxRoot()));
        properties.setWorkerKeepSandbox(booleanValue(normalized.get("WORKER_KEEP_SANDBOX"), properties.isWorkerKeepSandbox()));
        properties.setWorkerAllowedEnvNames(listValue(normalized.get("WORKER_ALLOWED_ENV_NAMES"),
                properties.getWorkerAllowedEnvNames()));
        properties.setWorkerBlockedEnvNameFragments(listValue(normalized.get("WORKER_BLOCKED_ENV_NAME_FRAGMENTS"),
                properties.getWorkerBlockedEnvNameFragments()));
        properties.setSensitivePathPatterns(listValue(normalized.get("SENSITIVE_PATH_PATTERNS"), properties.getSensitivePathPatterns()));
        return properties;
    }

    private static List<String> listValue(String value, List<String> defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(value.split("[;,]")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static long longValue(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private static String stringValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int intValue(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static boolean booleanValue(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
