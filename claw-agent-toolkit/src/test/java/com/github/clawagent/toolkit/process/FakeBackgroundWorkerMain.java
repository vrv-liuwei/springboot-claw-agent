package com.github.clawagent.toolkit.process;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class FakeBackgroundWorkerMain {
    private FakeBackgroundWorkerMain() {
    }

    public static void main(String[] args) {
        long pid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(ProcessHandle.current().pid());
        String stdout = "backgroundPid: " + pid + "\n"
                + "backgroundAlive: true\n"
                + "backgroundExitCode: \n"
                + "backgroundLogPath: fake.log\n"
                + "backgroundWaitMs: 50";
        System.out.println("CLAW_WORKER_RESULT_V1");
        System.out.println("exitCode: 0");
        System.out.println("timedOut: false");
        System.out.println("elapsedMs: 11");
        System.out.println("stdoutTruncated: false");
        System.out.println("stderrTruncated: false");
        System.out.println("resourceLimited: false");
        System.out.println("resourceLimitReason: ");
        System.out.println("cpuTimeMs: 0");
        System.out.println("memoryBytes: 0");
        System.out.println("workerEnvBlockedCount: 2");
        System.out.println("stdoutBase64: " + Base64.getEncoder().encodeToString(stdout.getBytes(StandardCharsets.UTF_8)));
        System.out.println("stderrBase64: ");
    }
}
