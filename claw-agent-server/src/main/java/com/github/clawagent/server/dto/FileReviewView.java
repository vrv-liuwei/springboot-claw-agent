package com.github.clawagent.server.dto;

/**
 * 文件审查详情。
 *
 * @param change 变更摘要。
 * @param beforeContent 修改前内容；新建文件为空。
 * @param afterContent 修改后当前内容；删除文件为空。
 */
public record FileReviewView(
        FileChangeView change,
        String beforeContent,
        String afterContent
) {
}
