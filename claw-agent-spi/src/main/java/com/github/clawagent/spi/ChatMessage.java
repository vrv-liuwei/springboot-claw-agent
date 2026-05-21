package com.github.clawagent.spi;

/**
 * 发给模型的单条消息。
 * role 使用 OpenAI 兼容协议的 system / user / assistant。
 */
public record ChatMessage(String role, String content) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
