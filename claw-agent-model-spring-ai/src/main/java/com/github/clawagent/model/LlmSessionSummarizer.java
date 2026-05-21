package com.github.clawagent.model;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.SessionSummarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于模型的会话摘要生成器。
 * 摘要只保存主题、结论、待办和关键上下文，避免把完整历史直接塞入长期记忆。
 */
public class LlmSessionSummarizer implements SessionSummarizer {
    private static final Logger log = LoggerFactory.getLogger(LlmSessionSummarizer.class);

    private final ModelClient modelClient;
    private final ChatOptions options;

    public LlmSessionSummarizer(ModelClient modelClient, ChatOptions options) {
        this.modelClient = modelClient;
        this.options = options;
    }

    @Override
    public String summarize(AgentSession session, List<AgentMessage> messages) {
        log.info("llm session summary started sessionId={} model={} messageCount={}", session.id(), options.model(), messages.size());
        String summary = modelClient.chat(List.of(
                ChatMessage.system("你是 ClawAgent 的会话摘要器。请用中文生成紧凑摘要，只保留用户目标、已确认决策、关键事实、待办事项和风险。不要编造历史中没有的信息。"),
                ChatMessage.user(buildPrompt(session, messages))
        ), options);
        log.info("llm session summary finished sessionId={} summaryLength={}", session.id(), summary.length());
        log.debug("llm session summary content sessionId={} content={}", session.id(), summary);
        return summary.trim();
    }

    private String buildPrompt(AgentSession session, List<AgentMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("会话标题：").append(session.title()).append("\n");
        prompt.append("已有摘要：").append(session.summary() == null ? "" : session.summary()).append("\n\n");
        prompt.append("会话消息：\n");
        int start = Math.max(0, messages.size() - 40);
        for (int i = start; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            // 控制单条消息长度，避免摘要请求本身无限增长。
            prompt.append("[").append(message.role()).append("] ")
                    .append(preview(message.content(), 1200))
                    .append("\n");
        }
        prompt.append("\n请输出 5 到 10 条要点，不要输出无关解释。");
        return prompt.toString();
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
