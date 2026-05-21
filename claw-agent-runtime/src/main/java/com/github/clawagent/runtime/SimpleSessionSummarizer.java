package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.spi.SessionSummarizer;

import java.util.List;

/**
 * 本地规则会话摘要生成器。
 * 作为 rule 模式或模型不可用时的兜底实现，只提取最近消息摘要，不调用外部模型。
 */
public class SimpleSessionSummarizer implements SessionSummarizer {
    @Override
    public String summarize(AgentSession session, List<AgentMessage> messages) {
        if (messages.isEmpty()) {
            return "暂无会话消息。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("会话 ").append(session.title()).append(" 最近内容：");
        int start = Math.max(0, messages.size() - 6);
        for (int i = start; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            // 只截取短摘要，避免把完整上下文复制到 session.summary。
            builder.append("\n- ")
                    .append(message.role())
                    .append(": ")
                    .append(preview(message.content()));
        }
        return builder.toString();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
