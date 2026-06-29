package com.github.clawagent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.AgentResponseGenerator;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.ModelImageInput;
import com.github.clawagent.spi.MultimodalModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于真实模型的最终回复生成器。
 * 工具结果只作为上下文输入给模型，最终回答不再由 Runtime 直接拼接。
 */
public class LlmResponseGenerator implements AgentResponseGenerator {
    private static final Logger log = LoggerFactory.getLogger(LlmResponseGenerator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ATTACHMENTS_KEY = "attachments";
    private static final String NATIVE_VISION_KEY = "attachments.nativeVision";

    private final ModelClient modelClient;
    private final ChatOptions options;

    public LlmResponseGenerator(ModelClient modelClient, ChatOptions options) {
        this.modelClient = modelClient;
        this.options = options;
    }

    @Override
    public String generate(AgentTask task, List<AgentStep> steps) {
        log.info("llm response generation started taskId={} model={} stepCount={}", task.id(), options.model(), steps.size());
        List<ChatMessage> messages = List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user(buildUserPrompt(task, steps)));
        List<ModelImageInput> images = nativeVisionImages(task);
        String content = !images.isEmpty() && modelClient instanceof MultimodalModelClient multimodalModelClient
                ? multimodalModelClient.chatWithImages(messages, images, options)
                : modelClient.chat(messages, options);
        log.info("llm response generation finished taskId={} answerLength={}", task.id(), content.length());
        log.debug("llm response content taskId={} content={}", task.id(), content);
        return content.trim();
    }

    protected String systemPrompt() {
        return "你是 ClawAgent 企业级 Harness Agent。请用中文回答用户。若提供了工具结果，只能基于工具结果和用户问题回答，不要伪造未执行的事实。"
                + "Todo 状态展示必须一致：pending/未执行/待执行只能使用灰点或 ⏳，running/执行中使用红点或 🔴，completed/已完成/完成才可以使用 ✅，failed/失败使用 ❌。"
                + "禁止输出“✅ pending”“✅ 未执行”“✅ 待执行”这类互相矛盾的状态。";
    }

    protected String buildUserPrompt(AgentTask task, List<AgentStep> steps) {
        StringBuilder prompt = new StringBuilder();
        String knowledge = LlmAgentPlanner.knowledgeContext(task);
        if (!knowledge.isBlank()) {
            prompt.append("知识库上下文：\n").append(knowledge).append("\n\n");
        }
        String attachments = LlmAgentPlanner.attachmentContext(task);
        if (!attachments.isBlank()) {
            prompt.append("附件理解上下文：\n").append(attachments).append("\n\n");
        }
        String memory = LlmAgentPlanner.memoryContext(task);
        if (!memory.isBlank()) {
            prompt.append("记忆上下文：\n").append(memory).append("\n\n");
        }
        String context = LlmAgentPlanner.sessionContext(task);
        if (!context.isBlank()) {
            prompt.append("近期会话上下文：\n").append(context).append("\n\n");
        }
        prompt.append("用户请求：").append(LlmAgentPlanner.displayUserInput(task)).append("\n\n");
        if (steps.isEmpty()) {
            prompt.append("没有执行工具，请直接基于你的模型能力回答。");
            return prompt.toString();
        }

        // 将每个工具步骤压缩为可读上下文，交给模型做最终组织和解释。
        prompt.append("已执行工具结果：\n");
        for (AgentStep step : steps) {
            prompt.append("- tool=").append(step.name())
                    .append(", status=").append(step.status())
                    .append(", input=").append(step.input())
                    .append(", output=").append(step.output())
                    .append(", error=").append(step.error())
                    .append("\n");
        }
        prompt.append("\n请给出最终回答。");
        return prompt.toString();
    }

    protected List<ModelImageInput> nativeVisionImages(AgentTask task) {
        if (task == null || task.metadata() == null
                || !"true".equalsIgnoreCase(task.metadata().getOrDefault(NATIVE_VISION_KEY, ""))) {
            return List.of();
        }
        String raw = task.metadata().get(ATTACHMENTS_KEY);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = OBJECT_MAPPER.readValue(raw, new TypeReference<>() {});
            List<ModelImageInput> images = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String localPath = text(item, "localPath");
                String mimeType = text(item, "mimeType", "contentType");
                String fileName = text(item, "fileName", "name");
                if (localPath.isBlank() || !looksLikeImage(text(item, "type", "kind"), mimeType, fileName)) {
                    continue;
                }
                Path path = Path.of(localPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) {
                    images.add(new ModelImageInput(path.toString(), firstNonBlank(mimeType, "image/png"), fileName));
                }
            }
            return images;
        } catch (Exception e) {
            log.warn("native vision attachments parse failed taskId={} error={}", task.id(), e.getMessage());
            return List.of();
        }
    }

    private boolean looksLikeImage(String type, String mimeType, String fileName) {
        String explicitType = normalize(type);
        if ("image".equals(explicitType)) {
            return true;
        }
        String mime = normalize(mimeType);
        if (mime.startsWith("image/")) {
            return true;
        }
        String name = normalize(fileName);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    private String text(Map<String, Object> item, String... keys) {
        if (item == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
