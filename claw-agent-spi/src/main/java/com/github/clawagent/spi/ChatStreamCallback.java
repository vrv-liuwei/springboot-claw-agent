package com.github.clawagent.spi;

/**
 * 模型流式输出回调。
 */
public interface ChatStreamCallback {
    void onDelta(String content);

    default void onComplete(String content) {
    }
}
