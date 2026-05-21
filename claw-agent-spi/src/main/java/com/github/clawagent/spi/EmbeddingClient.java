package com.github.clawagent.spi;

import java.util.List;

/**
 * EmbeddingClient 是向量化模型 SPI。
 * VectorStore、Markdown Memory 和任务摘要索引都通过它获取向量，不直接绑定具体厂商 SDK。
 */
public interface EmbeddingClient {
    EmbeddingResult embed(String text, EmbeddingOptions options);

    List<EmbeddingResult> embedAll(List<String> texts, EmbeddingOptions options);
}
