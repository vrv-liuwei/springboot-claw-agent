package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 任务恢复请求。
 * 前端只传用户可见的恢复意图和本次权限 metadata，后端从原 task 读取会话和恢复点。
 *
 * @param input 可选的恢复提示；为空时后端按原任务恢复点生成。
 * @param channelId 可选渠道 ID。
 * @param userId 可选用户 ID。
 * @param metadata 本次恢复沿用的权限、知识库等轻量元数据。
 */
public record ResumeTaskRequest(
        String input,
        String channelId,
        String userId,
        Map<String, String> metadata
) {
}
