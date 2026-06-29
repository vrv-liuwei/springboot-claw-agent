package com.github.clawagent.spring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AttachmentParseResult;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.knowledge.AttachmentService;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.model.OpenAiCompatibleModelClient;
import com.github.clawagent.spi.AgentRuntimeInterceptor;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.FileStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一处理 metadata.attachments。
 * input 只保留用户文字问题，图片和文件都在 Runtime 入口转换成模型可消费的上下文。
 */
public class MediaAttachmentRuntimeInterceptor implements AgentRuntimeInterceptor {
    public static final String ATTACHMENTS_KEY = "attachments";
    public static final String MODEL_CONTEXT_KEY = "attachments.modelContext";
    public static final String NATIVE_VISION_KEY = "attachments.nativeVision";
    private static final String PREPROCESSED_KEY = "attachments.preprocessed";
    private static final Logger log = LoggerFactory.getLogger(MediaAttachmentRuntimeInterceptor.class);

    private final ClawAgentProperties properties;
    private final FileStorageProvider storageProvider;
    private final AttachmentService attachmentService;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MediaAttachmentRuntimeInterceptor(ClawAgentProperties properties,
                                             FileStorageProvider storageProvider,
                                             AttachmentService attachmentService,
                                             KnowledgeService knowledgeService) {
        this.properties = properties;
        this.storageProvider = storageProvider;
        this.attachmentService = attachmentService;
        this.knowledgeService = knowledgeService;
    }

    @Override
    public int order() {
        return -50;
    }

    @Override
    public AgentRequest beforeRequest(AgentRequest request) {
        if (request == null || request.metadata() == null || "true".equalsIgnoreCase(request.metadata().get(PREPROCESSED_KEY))) {
            return request;
        }
        List<AttachmentItem> attachments = parseAttachments(request.metadata().get(ATTACHMENTS_KEY));
        if (attachments.isEmpty()) {
            return request;
        }
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put(PREPROCESSED_KEY, "true");
        StringBuilder extraInput = new StringBuilder();
        List<String> knowledgeDocumentIds = new ArrayList<>(parseCsv(metadata.get(KnowledgeService.METADATA_ATTACHMENT_DOCUMENT_IDS_KEY)));
        boolean defaultSupportsVision = defaultModelSupportsVision();

        for (AttachmentItem attachment : attachments) {
            String type = resolveType(attachment);
            log.info("attachment preprocessing route type={} source={} fileName={} id={}",
                    type, attachment.source(), attachment.fileName(), attachment.attachmentId());
            if ("image".equals(type)) {
                if (defaultSupportsVision) {
                    Path imagePath = resolveLocalPath(attachment);
                    String fileName = firstNonBlank(attachment.fileName(), imagePath == null ? "" : imagePath.getFileName().toString(), "image");
                    if (imagePath == null || !Files.isRegularFile(imagePath)) {
                        appendSection(extraInput, "Image", fileName, "Description", "图片文件不存在或无法定位，无法进行图片理解。");
                    } else {
                        metadata.put(NATIVE_VISION_KEY, "true");
                        appendSection(extraInput, "Image", fileName, "Status", "图片将直接交给默认视觉模型理解。");
                        log.info("attachment image routed to native default model vision fileName={} path={}", fileName, imagePath);
                    }
                } else {
                    appendImageUnderstanding(extraInput, attachment);
                }
            } else {
                appendFileContext(extraInput, attachment, request.userId(), knowledgeDocumentIds);
            }
        }

        if (!knowledgeDocumentIds.isEmpty()) {
            metadata.put(KnowledgeService.METADATA_ATTACHMENT_DOCUMENT_IDS_KEY, String.join(",", knowledgeDocumentIds.stream().distinct().toList()));
            metadata.put(KnowledgeService.METADATA_ENABLED_KEY, "true");
            metadata.put(KnowledgeService.METADATA_SCOPE_KEY, "attachments");
        }

        String modelContext = firstNonBlank(extraInput.toString());
        if (!modelContext.isBlank()) {
            metadata.put(MODEL_CONTEXT_KEY, modelContext);
        }
        AgentRequest enhanced = new AgentRequest(request.input(), request.sessionId(), request.channelId(), request.userId(), metadata);
        return knowledgeDocumentIds.isEmpty() ? enhanced : knowledgeService.enrichForModel(enhanced);
    }

    private List<AttachmentItem> parseAttachments(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(raw, new TypeReference<>() {});
            List<AttachmentItem> attachments = new ArrayList<>();
            for (Map<String, Object> item : items) {
                if (item != null) {
                    attachments.add(AttachmentItem.from(item));
                }
            }
            return attachments;
        } catch (Exception e) {
            log.warn("metadata.attachments parse failed error={}", e.getMessage());
            return List.of();
        }
    }

    private void appendImageUnderstanding(StringBuilder target, AttachmentItem attachment) {
        Path imagePath = resolveLocalPath(attachment);
        String fileName = firstNonBlank(attachment.fileName(), imagePath == null ? "" : imagePath.getFileName().toString(), "image");
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            appendSection(target, "Image", fileName, "Description", "图片文件不存在或无法定位，无法进行图片理解。");
            return;
        }
        String visionModelId = properties.getModel().getVisionModel();
        if (visionModelId == null || visionModelId.isBlank()) {
            appendSection(target, "Image", fileName, "Description", "当前未配置 clawagent.model.vision-model，无法进行图片理解。");
            return;
        }
        ClawAgentProperties.ModelConfig config = properties.getModels().get(visionModelId);
        if (config == null) {
            appendSection(target, "Image", fileName, "Description", "图片理解模型配置不存在：" + visionModelId);
            return;
        }
        try {
            OpenAiCompatibleModelClient client = new OpenAiCompatibleModelClient(config.getBaseUrl(), resolveApiKey(config));
            ChatOptions options = new ChatOptions(config.getModel(), config.getTemperature(), config.getTimeoutSeconds());
            String prompt = "请用中文描述这张图片的主要内容、可见文字、关键对象和用户可能关心的信息。";
            String description = client.chatWithImage(prompt, imagePath, firstNonBlank(attachment.mimeType(), "image/png"), options);
            appendSection(target, "Image", fileName, "Description", description);
        } catch (Exception e) {
            log.warn("image understanding failed fileName={} error={}", fileName, e.getMessage());
            appendSection(target, "Image", fileName, "Description", "图片理解失败：" + e.getMessage());
        }
    }

    private boolean defaultModelSupportsVision() {
        String defaultModelId = properties.getModel().getDefault();
        if (defaultModelId == null || defaultModelId.isBlank()) {
            return false;
        }
        ClawAgentProperties.ModelConfig config = properties.getModels().get(defaultModelId);
        return config != null && config.isVision();
    }

    private void appendFileContext(StringBuilder target, AttachmentItem attachment, String userId, List<String> knowledgeDocumentIds) {
        String fileName = firstNonBlank(attachment.fileName(), attachment.attachmentId(), "attachment");
        String existingDocumentId = attachment.knowledgeDocumentId();
        if (!existingDocumentId.isBlank()) {
            knowledgeDocumentIds.add(existingDocumentId);
            appendSection(target, "File", fileName, "Status", "文件已按后台附件流程入库，回答时会优先检索该文件内容。");
            return;
        }
        Path path = resolveLocalPath(attachment);
        if (path == null || !Files.isRegularFile(path)) {
            appendSection(target, "File", fileName, "Status", "文件不存在或无法定位，无法解析。");
            return;
        }
        try {
            byte[] content = Files.readAllBytes(path);
            List<AttachmentParseResult> results = attachmentService.parse(List.of(new AttachmentService.UploadFile(
                    fileName,
                    firstNonBlank(attachment.mimeType(), probeContentType(path)),
                    content)), userId);
            for (AttachmentParseResult result : results) {
                if (result.knowledgeDocumentId() != null && !result.knowledgeDocumentId().isBlank()) {
                    knowledgeDocumentIds.add(result.knowledgeDocumentId());
                }
                appendSection(target, "File", firstNonBlank(result.name(), fileName), "Status",
                        firstNonBlank(result.message(), "文件已按后台附件流程处理。"));
            }
        } catch (Exception e) {
            log.warn("file attachment preprocessing failed fileName={} error={}", fileName, e.getMessage());
            appendSection(target, "File", fileName, "Status", "文件解析失败：" + e.getMessage());
        }
    }

    private Path resolveLocalPath(AttachmentItem attachment) {
        if (attachment.localPath() != null && !attachment.localPath().isBlank()) {
            return Path.of(attachment.localPath()).toAbsolutePath().normalize();
        }
        String attachmentId = attachment.attachmentId();
        if (attachmentId == null || attachmentId.isBlank()) {
            return null;
        }
        try {
            StoredFile stored = storageProvider.read(attachmentId);
            return stored.localPath() == null ? null : stored.localPath().toAbsolutePath().normalize();
        } catch (Exception e) {
            log.warn("attachment local path resolve failed id={} error={}", attachmentId, e.getMessage());
            return null;
        }
    }

    private String resolveType(AttachmentItem attachment) {
        String type = normalize(attachment.type());
        if (!type.isBlank()) {
            if ("image".equals(type) || "file".equals(type) || "video".equals(type)) {
                return type;
            }
        }
        String mimeType = normalize(attachment.mimeType());
        if (mimeType.startsWith("image/")) {
            return "image";
        }
        String name = normalize(attachment.fileName());
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")) {
            return "image";
        }
        return "file";
    }

    private void appendSection(StringBuilder target, String type, String fileName, String label, String body) {
        if (!target.isEmpty()) {
            target.append("\n\n");
        }
        target.append("[").append(type).append(": ").append(firstNonBlank(fileName, "-")).append("]\n")
                .append(label).append(":\n")
                .append(body == null ? "" : body.trim());
    }

    private String resolveApiKey(ClawAgentProperties.ModelConfig config) {
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey();
        }
        String env = config.getApiKeyEnv();
        return env == null || env.isBlank() ? null : System.getenv(env);
    }

    private String probeContentType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type == null || type.isBlank() ? "application/octet-stream" : type;
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private record AttachmentItem(
            String type,
            String attachmentId,
            String localPath,
            String fileName,
            String mimeType,
            String source,
            String knowledgeDocumentId
    ) {
        static AttachmentItem from(Map<String, Object> item) {
            return new AttachmentItem(
                    text(item, "type", "kind"),
                    text(item, "attachmentId", "id"),
                    text(item, "localPath"),
                    text(item, "fileName", "name"),
                    text(item, "mimeType", "contentType"),
                    text(item, "source"),
                    text(item, "knowledgeDocumentId"));
        }

        private static String text(Map<String, Object> item, String... keys) {
            for (String key : keys) {
                Object value = item.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
            return "";
        }
    }
}
