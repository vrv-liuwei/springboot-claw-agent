package com.github.clawagent.spi;

import java.util.ArrayList;
import java.util.List;

/**
 * 线程级 LLM trace 缓冲。
 * Planner/ResponseGenerator 内部调用模型后，Runtime 会 drain 并写入当前 task/session 的事件日志。
 */
public final class LlmTraceContext {
    private static final ThreadLocal<List<LlmCallTrace>> TRACES = ThreadLocal.withInitial(ArrayList::new);

    private LlmTraceContext() {
    }

    public static void add(LlmCallTrace trace) {
        TRACES.get().add(trace);
    }

    public static List<LlmCallTrace> drain() {
        List<LlmCallTrace> traces = new ArrayList<>(TRACES.get());
        TRACES.get().clear();
        return traces;
    }

    public static void clear() {
        TRACES.get().clear();
    }
}
