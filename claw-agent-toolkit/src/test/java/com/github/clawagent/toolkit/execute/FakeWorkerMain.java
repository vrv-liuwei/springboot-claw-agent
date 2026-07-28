package com.github.clawagent.toolkit.execute;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class FakeWorkerMain {
    private FakeWorkerMain() {
    }

    public static void main(String[] args) {
        System.out.println("CLAW_WORKER_RESULT_V1");
        System.out.println("exitCode: 0");
        System.out.println("timedOut: false");
        System.out.println("elapsedMs: 7");
        System.out.println("stdoutTruncated: false");
        System.out.println("stderrTruncated: false");
        System.out.println("resourceLimited: false");
        System.out.println("resourceLimitReason: ");
        System.out.println("cpuTimeMs: 9");
        System.out.println("memoryBytes: 1024");
        System.out.println("workerEnvBlockedCount: 0");
        System.out.println("workerSandboxPath: fake-sandbox");
        System.out.println("workerSandboxKept: false");
        System.out.println("stdoutBase64: " + encode("fake worker ok\nargs: " + String.join(" ", args)));
        System.out.println("stderrBase64: ");
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
