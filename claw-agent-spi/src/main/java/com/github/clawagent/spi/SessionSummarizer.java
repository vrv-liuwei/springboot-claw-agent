package com.github.clawagent.spi;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;

import java.util.List;

/**
 * 会话摘要生成器。
 * Runtime 只依赖该 SPI，具体可以由 LLM、本地规则或企业自定义摘要服务实现。
 */
public interface SessionSummarizer {
    String summarize(AgentSession session, List<AgentMessage> messages);
}
