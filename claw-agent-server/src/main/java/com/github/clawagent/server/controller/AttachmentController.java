package com.github.clawagent.server.controller;

import com.github.clawagent.core.StoredFile;
import com.github.clawagent.knowledge.AttachmentService;
import com.github.clawagent.server.dto.AttachmentParseResponse;
import com.github.clawagent.spi.FileStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天附件解析和文件预览接口。
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {
    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    private final AttachmentService attachmentService;
    private final FileStorageProvider fileStorageProvider;

    public AttachmentController(AttachmentService attachmentService, FileStorageProvider fileStorageProvider) {
        this.attachmentService = attachmentService;
        this.fileStorageProvider = fileStorageProvider;
    }

    /**
     * 上传附件并返回给模型可消费的轻量解析结果。
     */
    @PostMapping(value = "/attachments/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentParseResponse parseAttachments(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(name = "userId", defaultValue = "console") String userId) throws IOException {
        int count = files == null ? 0 : files.size();
        log.info("attachment parse requested count={} userId={}", count, userId);
        List<AttachmentService.UploadFile> uploadFiles = new ArrayList<>();
        for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
            uploadFiles.add(new AttachmentService.UploadFile(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        }
        // 解析策略保持在 knowledge 模块，Controller 只做 Multipart 到领域对象的转换。
        return new AttachmentParseResponse(attachmentService.parse(uploadFiles, userId));
    }

    /**
     * 下载附件原文件。
     */
    @GetMapping("/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable("attachmentId") String attachmentId) throws IOException {
        return attachmentResponse(attachmentId, true);
    }

    /**
     * 内联预览附件原文件。
     */
    @GetMapping("/attachments/{attachmentId}/view")
    public ResponseEntity<Resource> viewAttachment(@PathVariable("attachmentId") String attachmentId) throws IOException {
        return attachmentResponse(attachmentId, false);
    }

    private ResponseEntity<Resource> attachmentResponse(String attachmentId, boolean download) throws IOException {
        StoredFile metadata = fileStorageProvider.read(attachmentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(metadata.originalName(), StandardCharsets.UTF_8)
                .build();
        // 原文件只通过专用附件接口输出，避免聊天消息、SSE 或任务事件携带大正文。
        return ResponseEntity.ok()
                .contentType(attachmentMediaType(metadata.contentType()))
                .contentLength(metadata.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(metadata.localPath()));
    }

    private MediaType attachmentMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
