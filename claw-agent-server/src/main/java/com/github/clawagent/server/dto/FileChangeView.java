package com.github.clawagent.server.dto;

import java.time.Instant;

/**
 * 单个任务内的文件变更摘要。
 *
 * @param id 前端列表稳定 key。
 * @param taskId 所属任务。
 * @param stepId 产生变更的工具步骤。
 * @param toolId 工具 ID。
 * @param changeType create/modify/append/rollback/delete。
 * @param path 目标文件绝对路径。
 * @param backupPath 修改前备份路径；新建文件通常为空。
 * @param diff 工具生成的轻量 diff 摘要。
 * @param addedLines 新增行数量。
 * @param deletedLines 删除行数量。
 * @param todoId 产生变更时正在执行的 Todo。
 * @param todoOrder Todo 顺序号。
 * @param todoTitle Todo 标题。
 * @param createdAt 事件时间。
 * @param reviewStatus 审查状态：latest/rolled-back/failed。
 * @param supersededCount 同一路径被后续变更折叠掉的历史记录数。
 */
public record FileChangeView(
        String id,
        String taskId,
        String stepId,
        String toolId,
        String changeType,
        String path,
        String backupPath,
        String diff,
        int addedLines,
        int deletedLines,
        String todoId,
        String todoOrder,
        String todoTitle,
        Instant createdAt,
        String reviewStatus,
        int supersededCount
) {
}
