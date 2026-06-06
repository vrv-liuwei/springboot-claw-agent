package com.github.clawagent.knowledge;

import com.github.clawagent.core.AttachmentParseResult;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.spi.FileStorageProvider;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 附件解析与入库服务。
 * 该服务属于 knowledge 模块，server 只负责把 MultipartFile 转成 UploadFile。
 */
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final FileStorageProvider storageProvider;
    private final KnowledgeService knowledgeService;
    private final Tika tika = new Tika();

    /**
     * @param storageProvider 文件存储 provider，默认 local，后续可替换为 MinIO。
     * @param knowledgeService 知识库统一入口，用于附件自动入库。
     */
    public AttachmentService(FileStorageProvider storageProvider, KnowledgeService knowledgeService) {
        this.storageProvider = storageProvider;
        this.knowledgeService = knowledgeService;
    }

    /**
     * 解析上传附件并写入本地文件存储和知识库。
     */
    public List<AttachmentParseResult> parse(List<UploadFile> files, String userId) {
        List<AttachmentParseResult> results = new ArrayList<>();
        for (UploadFile file : files == null ? List.<UploadFile>of() : files) {
            results.add(parseOne(file, normalizeUserId(userId)));
        }
        return results;
    }

    private AttachmentParseResult parseOne(UploadFile file, String userId) {
        if (file == null || file.content() == null || file.content().length == 0) {
            return failed(null, file == null ? "" : file.name(), "附件为空");
        }
        if (file.content().length > MAX_FILE_BYTES) {
            return failed(null, file.name(), "附件超过最大限制 20MB");
        }
        StoredFile stored = null;
        try {
            String kind = detectKind(file.contentType(), file.name());
            stored = storageProvider.save(file.name(), file.contentType(), file.content(), Map.of("source", "attachment"));
            int originalChars = "image".equals(kind) ? 0 : normalizeText(extractText(file)).length();
            KnowledgeDocument document = ingestKnowledge(file, kind, userId);
            // 附件正文只进入知识库，不再返回页面或拼进 input，避免历史消息和请求体膨胀。
            return result(stored, kind, originalChars, false,
                    "image".equals(kind) ? "图片已上传并记录元信息" : "解析成功，内容已入库",
                    document);
        } catch (Exception e) {
            log.warn("attachment parse failed name={} error={}", file.name(), e.getMessage());
            return failed(stored, file.name(), "解析失败：" + e.getMessage());
        }
    }

    private KnowledgeDocument ingestKnowledge(UploadFile file, String kind, String userId) {
        try {
            // 知识库按 userId 隔离；附件上传接口未传 userId 时沿用控制台默认用户。
            return knowledgeService.ingest(
                    userId,
                    safeName(file.name()),
                    safeContentType(file.contentType()),
                    kind,
                    file.content(),
                    Map.of("source", "attachment"));
        } catch (Exception e) {
            log.warn("attachment knowledge ingest skipped name={} error={}", file.name(), e.getMessage());
            return null;
        }
    }

    private String extractText(UploadFile file) throws Exception {
        try (ByteArrayInputStream input = new ByteArrayInputStream(file.content())) {
            return tika.parseToString(input);
        }
    }

    private String detectKind(String contentType, String filename) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        String name = filename == null ? "" : filename.toLowerCase();
        if (type.startsWith("image/")) {
            return "image";
        }
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            return "pdf";
        }
        if (type.contains("word") || name.endsWith(".doc") || name.endsWith(".docx")) {
            return "word";
        }
        if (type.contains("excel") || type.contains("spreadsheet") || name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".csv")) {
            return "excel";
        }
        return "text";
    }

    private AttachmentParseResult result(StoredFile stored,
                                         String kind,
                                         int originalChars,
                                         boolean truncated,
                                         String message,
                                         KnowledgeDocument document) {
        return new AttachmentParseResult(
                stored.id(),
                stored.originalName(),
                stored.contentType(),
                stored.size(),
                kind,
                stored.provider(),
                stored.providerPath(),
                "",
                originalChars,
                0,
                truncated,
                message,
                document == null ? "" : document.id(),
                document == null ? "" : document.provider(),
                document == null ? "" : document.providerDocumentId());
    }

    private AttachmentParseResult failed(StoredFile stored, String name, String message) {
        if (stored != null) {
            return new AttachmentParseResult(stored.id(), stored.originalName(), stored.contentType(), stored.size(),
                    "unknown", stored.provider(), stored.providerPath(), "", 0, 0, false, message, "", "", "");
        }
        return new AttachmentParseResult("", safeName(name), "", 0, "unknown", "", "", "", 0, 0, false, message, "", "", "");
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n")
                .trim();
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "attachment" : name;
    }

    private String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    /**
     * 附件上传文件的最小输入模型，避免 knowledge 模块依赖 Spring MultipartFile。
     *
     * @param name 上传文件名。
     * @param contentType 文件 MIME 类型。
     * @param content 文件原始字节。
     */
    public record UploadFile(String name, String contentType, byte[] content) {
    }
}
