package com.github.clawagent.server.dto;

/**
 * 管理台文件审查里的手动回滚请求。
 * path/stepId 必须来自任务变更列表，避免前端拼任意路径触发文件恢复。
 */
public record RollbackTaskFileRequest(
        String stepId,
        String path,
        String charset
) {
}
