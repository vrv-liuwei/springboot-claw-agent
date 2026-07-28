package com.github.clawagent.worker;

import java.nio.file.Files;
import java.nio.file.Path;

final class WorkerTestProcess {
    private WorkerTestProcess() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "" : args[0];
        if ("emit-large".equals(mode)) {
            System.out.print("x".repeat(512));
            return;
        }
        if ("spawn-grandchild".equals(mode)) {
            Path pidFile = Path.of(args[1]);
            Process child = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"),
                    WorkerTestProcess.class.getName(), "sleep").start();
            Files.writeString(pidFile, Long.toString(child.pid()));
            Thread.sleep(30_000);
            return;
        }
        if ("sleep".equals(mode)) {
            Thread.sleep(30_000);
        }
        if ("allocate-memory".equals(mode)) {
            int megabytes = Integer.parseInt(args[1]);
            byte[][] blocks = new byte[megabytes][];
            for (int i = 0; i < megabytes; i++) {
                blocks[i] = new byte[1024 * 1024];
                blocks[i][0] = (byte) i;
                Thread.sleep(20);
            }
            Thread.sleep(30_000);
        }
        if ("busy-loop".equals(mode)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            long value = 0;
            while (System.nanoTime() < deadline) {
                value += System.nanoTime() & 7;
            }
            System.out.print(value);
        }
        if ("print-env".equals(mode)) {
            String name = args[1];
            String value = System.getenv(name);
            System.out.print(value == null ? "MISSING" : value);
        }
        if ("subagent-plain".equals(mode)) {
            String payload = new String(System.in.readAllBytes());
            System.out.print(payload.contains("CLAW_SUBAGENT_WORKER_V1") ? "child runtime ok" : "missing protocol");
        }
        if ("subagent-marked".equals(mode)) {
            System.in.readAllBytes();
            System.out.println("runtime log");
            System.out.println("CLAW_SUBAGENT_WORKER_RESULT_V1");
            System.out.println("{\"answer\":\"delegated ok\",\"status\":\"COMPLETED\",\"metadata\":{\"worker.delegated\":\"yes\"}}");
        }
        if ("subagent-fail".equals(mode)) {
            System.in.readAllBytes();
            System.err.print("child runtime failed");
            System.exit(9);
        }
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
