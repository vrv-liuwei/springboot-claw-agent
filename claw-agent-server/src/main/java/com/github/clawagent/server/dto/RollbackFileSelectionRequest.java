package com.github.clawagent.server.dto;

/**
 * 文件审查里的选中行局部回滚请求。
 * selectedText 用于防止前端基于旧内容发起回滚后覆盖用户的新修改。
 */
public record RollbackFileSelectionRequest(
        String stepId,
        String path,
        String backupPath,
        int startLine,
        int endLine,
        String selectedText,
        String base,
        Integer insertAfterLine,
        String charset
) {
}
