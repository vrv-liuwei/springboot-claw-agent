package com.github.clawagent.skill;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Script Skill 的进程执行抽象。
 * 默认实现直接启动本机进程；Spring 场景可以注入 worker-backed 实现，把高危脚本纳入统一隔离。
 */
public interface SkillProcessExecutor {
    Result execute(List<String> commandLine, File cwd, Map<String, String> env, long timeoutMs) throws Exception;

    record Result(int exitCode, boolean timedOut, long elapsedMs, String stdout, String stderr,
                  boolean workerIsolated, boolean stdoutTruncated, boolean stderrTruncated,
                  boolean resourceLimited, String resourceLimitReason, long workerCpuTimeMs,
                  long workerMemoryBytes, long workerPoolWaitMs, int workerEnvBlockedCount) {
    }
}
