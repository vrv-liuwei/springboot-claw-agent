package com.github.clawagent.server.controller;

import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.server.dto.KnowledgeSearchPayload;
import com.github.clawagent.server.dto.KnowledgeSearchResponse;
import com.github.clawagent.server.dto.VectorStatusView;
import com.github.clawagent.server.service.VectorStatusQueryService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口，只负责 HTTP 入参出参适配。
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
    private final KnowledgeService knowledgeService;
    private final VectorStatusQueryService vectorStatusQueryService;

    public KnowledgeController(KnowledgeService knowledgeService,
                               VectorStatusQueryService vectorStatusQueryService) {
        this.knowledgeService = knowledgeService;
        this.vectorStatusQueryService = vectorStatusQueryService;
    }

    /**
     * 查询当前可用的知识库 Provider 能力。
     */
    @GetMapping("/knowledge/providers")
    public List<Map<String, Object>> knowledgeProviders() {
        return knowledgeService.providers();
    }

    /**
     * 上传知识库文档并触发解析入库。
     */
    @PostMapping(value = "/knowledge/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<KnowledgeDocument> uploadKnowledgeDocuments(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(name = "userId", defaultValue = "console") String userId) throws IOException {
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            String kind = detectKnowledgeKind(file.getContentType(), file.getOriginalFilename());
            // userId 在 KnowledgeService/provider 内继续校验隔离，Controller 只透传当前请求用户。
            documents.add(knowledgeService.ingest(
                    userId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    kind,
                    file.getBytes(),
                    Map.of("source", "admin-upload")));
        }
        return documents;
    }

    /**
     * 分页列出当前用户的知识库文档。
     */
    @GetMapping("/knowledge/documents")
    public List<KnowledgeDocument> knowledgeDocuments(
            @RequestParam(name = "userId", defaultValue = "console") String userId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return knowledgeService.list(userId, limit);
    }

    /**
     * 下载指定知识库原始文档。
     */
    @GetMapping("/knowledge/documents/{documentId}/download")
    public ResponseEntity<Resource> downloadKnowledgeDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        StoredFile file = knowledgeService.download(userId, documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(attachmentMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.localPath()));
    }

    /**
     * 删除指定知识库文档和相关索引数据。
     */
    @DeleteMapping("/knowledge/documents/{documentId}")
    public Map<String, Object> deleteKnowledgeDocument(
            @PathVariable("documentId") String documentId,
            @RequestParam(name = "userId", defaultValue = "console") String userId) {
        knowledgeService.delete(userId, documentId);
        return Map.of("deleted", true, "documentId", documentId);
    }

    /**
     * 按关键词、向量或混合模式检索知识库。
     */
    @PostMapping("/knowledge/search")
    public KnowledgeSearchResponse searchKnowledge(@RequestBody KnowledgeSearchPayload payload) {
        KnowledgeSearchPayload safePayload = payload == null ? new KnowledgeSearchPayload(null, null, List.of(), null, null) : payload;
        List<KnowledgeSearchResult> hits = knowledgeService.search(
                firstNonBlank(safePayload.userId(), "console"),
                safePayload.query(),
                safePayload.documentIds() == null ? List.of() : safePayload.documentIds(),
                firstNonBlank(safePayload.mode(), "hybrid"),
                safePayload.topK() == null ? 8 : safePayload.topK());
        return new KnowledgeSearchResponse(hits);
    }

    /**
     * 查询知识库文档的向量化进度。
     */
    @GetMapping("/knowledge/vector-status")
    public List<VectorStatusView> knowledgeVectorStatus(@RequestParam(name = "userId", defaultValue = "console") String userId) {
        return vectorStatusQueryService.knowledgeVectorStatus(userId);
    }

    private MediaType attachmentMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String detectKnowledgeKind(String contentType, String filename) {
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

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
