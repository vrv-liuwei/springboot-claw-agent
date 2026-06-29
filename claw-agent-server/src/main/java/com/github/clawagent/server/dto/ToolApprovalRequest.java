package com.github.clawagent.server.dto;

/**
 * 运行中工具审批请求；只放行当前 task/step 的一次工具调用，不修改全局权限配置。
 *
 * @param toolId 待审批的工具 ID。
 * @param reason 拒绝或审批说明。
 */
public record ToolApprovalRequest(String toolId, String reason) {
}
