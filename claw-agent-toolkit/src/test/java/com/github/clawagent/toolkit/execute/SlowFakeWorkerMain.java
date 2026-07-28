package com.github.clawagent.toolkit.execute;

public final class SlowFakeWorkerMain {
    private SlowFakeWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Thread.sleep(1_500);
        FakeWorkerMain.main(args);
    }
}
