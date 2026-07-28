package com.github.clawagent.intent;

import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统意图样例向量索引。
 * 启动时一次性把 examples / negativeExamples 向量化到内存，线上只计算用户 query 向量并做余弦相似度。
 */
public class EmbeddingIntentVectorIndex {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIntentVectorIndex.class);

    private final List<IntentExampleVector> examples;
    private final List<IntentExampleVector> negativeExamples;

    /**
     * 根据当前意图目录构建内存向量索引。
     * 如果未配置 embedding 客户端，则索引保持为空，路由服务会自动跳过语义匹配。
     */
    public EmbeddingIntentVectorIndex(List<IntentDefinition> definitions,
                                      EmbeddingClient embeddingClient,
                                      EmbeddingOptions embeddingOptions) {
        if (embeddingClient == null || embeddingOptions == null || definitions == null || definitions.isEmpty()) {
            this.examples = List.of();
            this.negativeExamples = List.of();
            return;
        }
        IntentVectorIndex built = build(definitions, embeddingClient, embeddingOptions);
        this.examples = built.examples();
        this.negativeExamples = built.negativeExamples();
    }

    /**
     * 判断是否具备可用于语义匹配的正例向量。
     */
    public boolean isEmpty() {
        return examples.isEmpty();
    }

    /**
     * 返回用户输入和某个意图所有正例之间的最高相似度。
     */
    public double bestExampleScore(List<Double> queryVector, IntentDefinition definition) {
        return bestScore(queryVector, examples, definition);
    }

    /**
     * 返回用户输入和某个意图所有反例之间的最高相似度，用于阻断“询问命令含义”等误触发。
     */
    public double bestNegativeScore(List<Double> queryVector, IntentDefinition definition) {
        return bestScore(queryVector, negativeExamples, definition);
    }

    /**
     * 批量收集正例和反例文本，并通过 embedAll 一次性预向量化。
     */
    private IntentVectorIndex build(List<IntentDefinition> definitions,
                                    EmbeddingClient embeddingClient,
                                    EmbeddingOptions embeddingOptions) {
        try {
            List<IntentExampleText> exampleTexts = new ArrayList<>();
            List<IntentExampleText> negativeTexts = new ArrayList<>();
            for (IntentDefinition definition : definitions) {
                for (String example : definition.examples()) {
                    if (example != null && !example.isBlank()) {
                        exampleTexts.add(new IntentExampleText(definition, example));
                    }
                }
                for (String negative : definition.negativeExamples()) {
                    if (negative != null && !negative.isBlank()) {
                        negativeTexts.add(new IntentExampleText(definition, negative));
                    }
                }
            }
            List<IntentExampleVector> exampleVectors = embedExamples(exampleTexts, embeddingClient, embeddingOptions);
            List<IntentExampleVector> negativeVectors = embedExamples(negativeTexts, embeddingClient, embeddingOptions);
            log.info("intent vector index initialized examples={} negativeExamples={}", exampleVectors.size(), negativeVectors.size());
            return new IntentVectorIndex(exampleVectors, negativeVectors);
        } catch (Exception e) {
            log.warn("intent vector index initialization skipped error={}", e.getMessage());
            return new IntentVectorIndex(List.of(), List.of());
        }
    }

    /**
     * 保持样例文本和向量结果按位置对应，避免向量化后丢失所属意图。
     */
    private List<IntentExampleVector> embedExamples(List<IntentExampleText> examples,
                                                    EmbeddingClient embeddingClient,
                                                    EmbeddingOptions embeddingOptions) {
        if (examples.isEmpty()) {
            return List.of();
        }
        List<String> texts = examples.stream().map(IntentExampleText::text).toList();
        List<EmbeddingResult> results = embeddingClient.embedAll(texts, embeddingOptions);
        List<IntentExampleVector> vectors = new ArrayList<>();
        int size = Math.min(examples.size(), results == null ? 0 : results.size());
        for (int i = 0; i < size; i++) {
            vectors.add(new IntentExampleVector(examples.get(i).definition(), examples.get(i).text(), results.get(i).vector()));
        }
        return vectors;
    }

    /**
     * 在同一个意图的样例集合内取最高余弦相似度。
     */
    private double bestScore(List<Double> queryVector, List<IntentExampleVector> vectors, IntentDefinition definition) {
        double best = 0;
        for (IntentExampleVector vector : vectors) {
            if (vector.definition().id().equals(definition.id())) {
                best = Math.max(best, cosine(queryVector, vector.vector()));
            }
        }
        return best;
    }

    /**
     * 计算余弦相似度；向量为空或范数为 0 时按 0 分处理。
     */
    private double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left == null ? 0 : left.size(), right == null ? 0 : right.size());
        if (size == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record IntentVectorIndex(List<IntentExampleVector> examples, List<IntentExampleVector> negativeExamples) {
    }

    private record IntentExampleText(IntentDefinition definition, String text) {
    }

    private record IntentExampleVector(IntentDefinition definition, String text, List<Double> vector) {
    }
}
