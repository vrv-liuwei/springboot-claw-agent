package com.github.clawagent.core;

/**
 * 工具执行结果。
 *
 * @param success 是否执行成功。
 * @param content 工具输出内容或错误说明。
 */
public record ToolResult(boolean success, String content) {
    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult error(String content) {
        return new ToolResult(false, content);
    }
}
