package com.github.clawagent.server.dto;

/**
 * 计划模板展示对象。模板只影响计划生成提示词，不直接执行任何工具。
 */
public record PlanTemplateView(
        String id,
        String title,
        String description,
        String mode,
        String promptHint
) {
}
