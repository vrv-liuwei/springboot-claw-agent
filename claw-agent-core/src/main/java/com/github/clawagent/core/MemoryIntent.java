package com.github.clawagent.core;

/**
 * 记忆意图识别结果。
 * <p>
 * 该对象只表达“是否值得沉淀为长期记忆”和建议字段，不直接写入数据库。
 * </p>
 *
 * @param shouldRemember 是否应该生成候选记忆。
 * @param scopeType 建议记忆范围，只允许 global、channel、session。
 * @param type 建议记忆类型，例如 preference、rule、decision、fact。
 * @param content 建议保存的记忆正文。
 * @param summary 建议展示摘要。
 * @param confidence 识别置信度，范围 0 到 1。
 * @param reason 判断理由，供管理台和调试查看。
 */
public record MemoryIntent(
        boolean shouldRemember,
        String scopeType,
        String type,
        String content,
        String summary,
        double confidence,
        String reason) {
}
