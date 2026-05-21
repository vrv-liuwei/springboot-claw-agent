package com.github.clawagent.memory.markdown;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.spi.MemoryPromoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Markdown 长期记忆提升器。
 * 会话摘要生成后，将摘要和最近消息短摘保存为 Markdown 文件。
 */
public class MarkdownMemoryPromoter implements MemoryPromoter {
    private static final Logger log = LoggerFactory.getLogger(MarkdownMemoryPromoter.class);

    private final MarkdownMemoryRepository repository;

    public MarkdownMemoryPromoter(MarkdownMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void promoteSessionSummary(AgentSession session, List<AgentMessage> messages) {
        Path path = repository.saveSessionSummary(session, messages);
        log.info("markdown memory promoted sessionId={} path={}", session.id(), path);
    }
}
