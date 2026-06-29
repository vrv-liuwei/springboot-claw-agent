package com.github.clawagent.server.dto;

/**
 * 打开文件请求；后端会先确认 path 属于该 task 的文件变更记录。
 *
 * @param stepId 产生文件变更的步骤。
 * @param path 文件绝对路径。
 * @param action vscode/explorer。
 */
public record OpenTaskFileRequest(
        String stepId,
        String path,
        String action
) {
}
