package com.github.clawagent.server.dto;

import com.github.clawagent.core.AttachmentParseResult;

import java.util.List;

/**
 * 附件解析 HTTP 响应。
 *
 * @param attachments 上传附件的轻量解析结果，不包含附件正文。
 */
public record AttachmentParseResponse(
        List<AttachmentParseResult> attachments
) {
}
