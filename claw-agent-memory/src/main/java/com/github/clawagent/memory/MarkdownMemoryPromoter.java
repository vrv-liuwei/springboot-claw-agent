package com.github.clawagent.memory;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.spi.MemoryPromoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Markdown 会话摘要提升器。
 * <p>
 * 该类保留旧 Markdown 记忆能力，但模块统一迁到 claw-agent-memory。
 * </p>
 */
public class MarkdownMemoryPromoter implements MemoryPromoter {
    private static final Logger log = LoggerFactory.getLogger(MarkdownMemoryPromoter.class);

    /** Markdown 兼容仓库。 */
    private final MarkdownMemoryRepository repository;

    /**
     * @param repository Markdown 兼容仓库。
     */
    public MarkdownMemoryPromoter(MarkdownMemoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void promoteSessionSummary(AgentSession session, List<AgentMessage> messages) {
        Path path = repository.saveSessionSummary(session, messages);
        log.info("markdown memory promoted sessionId={} path={}", session.id(), path);
    }
}
