package com.github.clawagent.model;

import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.LlmCallTrace;
import com.github.clawagent.spi.LlmTraceContext;
import com.github.clawagent.spi.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatClient 反射适配器。
 * 这个类不在编译期依赖 Spring AI，业务应用只要自己引入 Spring AI 并提供 ChatClient.Builder Bean 即可启用。
 */
public class SpringAiChatClientModelClient implements ModelClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiChatClientModelClient.class);

    /** Spring AI ChatClient 实例，运行时通过 ChatClient.Builder build 得到。 */
    private final Object chatClient;

    public SpringAiChatClientModelClient(Object chatClientBuilder) {
        this.chatClient = buildChatClient(chatClientBuilder);
    }

    @Override
    public String chat(List<ChatMessage> messages, ChatOptions options) {
        long startNanos = System.nanoTime();
        try {
            Object prompt = createPrompt(messages);
            Object response = invokePrompt(prompt);
            String content = content(response);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            LlmTraceContext.add(new LlmCallTrace(
                    options.model(),
                    "spring-ai",
                    200,
                    elapsedMs,
                    "SpringAI Prompt messages=" + messages.size(),
                    "SpringAI content length=" + content.length(),
                    content,
                    0,
                    0,
                    0));
            log.info("spring ai chat completed model={} messageCount={} elapsedMs={} answerLength={}",
                    options.model(), messages.size(), elapsedMs, content.length());
            return content;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Spring AI ChatClient 反射调用失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            LlmTraceContext.add(new LlmCallTrace(
                    options.model(),
                    "spring-ai",
                    500,
                    elapsedMs,
                    "SpringAI Prompt messages=" + messages.size(),
                    e.getMessage(),
                    "",
                    0,
                    0,
                    0));
            throw e;
        }
    }

    private Object buildChatClient(Object builder) {
        if (builder == null) {
            throw new IllegalArgumentException("Spring AI ChatClient.Builder 不能为空");
        }
        try {
            Method build = builder.getClass().getMethod("build");
            // 只依赖 Builder 的公开 build 方法，避免编译期绑定 Spring AI 版本。
            return build.invoke(builder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Spring AI ChatClient.Builder build 调用失败：" + e.getMessage(), e);
        }
    }

    private Object createPrompt(List<ChatMessage> messages) throws ReflectiveOperationException {
        Class<?> promptClass = Class.forName("org.springframework.ai.chat.prompt.Prompt");
        Constructor<?> constructor = promptClass.getConstructor(List.class);
        return constructor.newInstance(toSpringMessages(messages));
    }

    private List<Object> toSpringMessages(List<ChatMessage> messages) throws ReflectiveOperationException {
        List<Object> springMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            String role = message.role() == null ? "user" : message.role();
            String content = message.content() == null ? "" : message.content();
            // 通过类名反射创建 Spring AI Message，保持 ClawAgent core/spi 不感知 Spring AI 类型。
            springMessages.add(createMessage(role, content));
        }
        return springMessages;
    }

    private Object createMessage(String role, String content) throws ReflectiveOperationException {
        String className = switch (role) {
            case "system" -> "org.springframework.ai.chat.messages.SystemMessage";
            case "assistant" -> "org.springframework.ai.chat.messages.AssistantMessage";
            default -> "org.springframework.ai.chat.messages.UserMessage";
        };
        Class<?> messageClass = Class.forName(className);
        return messageClass.getConstructor(String.class).newInstance(content);
    }

    private Object invokePrompt(Object prompt) throws ReflectiveOperationException {
        Method promptMethod = chatClient.getClass().getMethod("prompt", prompt.getClass());
        Object requestSpec = promptMethod.invoke(chatClient, prompt);
        Method callMethod = requestSpec.getClass().getMethod("call");
        return callMethod.invoke(requestSpec);
    }

    private String content(Object response) throws ReflectiveOperationException {
        Method content = response.getClass().getMethod("content");
        Object value = content.invoke(response);
        return value == null ? "" : String.valueOf(value);
    }
}
