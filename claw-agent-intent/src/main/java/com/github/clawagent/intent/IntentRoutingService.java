package com.github.clawagent.intent;

import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 系统意图路由服务。
 * <p>
 * 入口消息先在这里完成“命令/文档意图”识别；明确命中的系统意图直接执行 handler 或进入确认，
 * 文档类意图只写入 metadata 后继续交给 AgentRuntime，未命中时不干预普通对话。
 */
public class IntentRoutingService {
    private static final Logger log = LoggerFactory.getLogger(IntentRoutingService.class);
    private final List<IntentDefinition> definitions;
    private final IntentHandlerRegistry handlerRegistry;
    private final PendingActionService pendingActionService;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingOptions embeddingOptions;
    private final boolean enabled;
    private final EmbeddingIntentVectorIndex vectorIndex;

    /**
     * 创建意图路由服务，并在构造阶段初始化样例向量索引。
     * 这里预计算 examples / negativeExamples，避免每条用户消息重复请求 embedding 模型。
     */
    public IntentRoutingService(List<IntentDefinition> definitions,
                                IntentHandlerRegistry handlerRegistry,
                                PendingActionService pendingActionService,
                                EmbeddingClient embeddingClient,
                                EmbeddingOptions embeddingOptions,
                                boolean enabled) {
        this.definitions = definitions == null ? List.of() : List.copyOf(definitions);
        this.handlerRegistry = handlerRegistry == null ? new IntentHandlerRegistry(Map.of()) : handlerRegistry;
        this.pendingActionService = pendingActionService;
        this.embeddingClient = embeddingClient;
        this.embeddingOptions = embeddingOptions;
        this.enabled = enabled;
        this.vectorIndex = new EmbeddingIntentVectorIndex(this.definitions, embeddingClient, embeddingOptions);
    }

    /**
     * 对单条用户输入执行意图路由。
     * 返回值会告诉 ChannelRouter：已处理、继续进模型，或者完全未命中。
     */
    public IntentRouteResult route(IntentRequest request) {
        if (!enabled || request == null) {
            return IntentRouteResult.notMatched();
        }
        IntentDefinition definition = match(request.input(), request.metadata()).orElse(null);
        if (definition == null) {
            return IntentRouteResult.notMatched();
        }
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        // 命中的意图写回 metadata，后续日志、模型上下文和审计都能看到同一份路由结果。
        metadata.put("intent.id", definition.id());
        metadata.put("intent.name", definition.name());
        metadata.put("intent.risk", definition.risk().metadataValue());
        metadata.putAll(definition.metadata());
        if (definition.routeMode() == IntentRouteMode.MODEL) {
            return IntentRouteResult.passToModel(metadata, definition.id(), 1.0);
        }
        IntentExecutionContext context = new IntentExecutionContext(definition, request.input(), request.sessionId(), request.channelId(), request.userId(), metadata);
        if (definition.risk() == IntentRisk.LOW) {
            return execute(context);
        }
        if (pendingActionService == null) {
            return IntentRouteResult.handled("检测到需要确认的操作【" + definition.name() + "】，但当前未启用确认服务。", definition.id(), 1.0);
        }
        // 中高风险系统意图不立即执行，统一创建待确认动作，由通道入口的 PendingActionService 二次确认。
        PendingAction action = pendingActionService.create(new PendingActionCreateRequest(
                PendingActionType.INTENT_CONFIRMATION,
                definition.name(),
                "系统意图需要用户确认后执行。",
                definition.risk(),
                request.sessionId(),
                request.channelId(),
                request.userId(),
                "",
                "",
                definition.id(),
                metadata,
                Duration.ofMinutes(2)),
                (pending, input) -> execute(context).answer());
        return IntentRouteResult.handled("检测到你要执行【" + definition.name() + "】。请回复：" + action.confirmText(), definition.id(), 1.0);
    }

    /**
     * 执行 handler 类型意图；handler 可直接回复，也可返回 metadata 继续进入模型。
     */
    private IntentRouteResult execute(IntentExecutionContext context) {
        return handlerRegistry.find(context.intent().handler())
                .map(handler -> {
                    IntentHandlerResult result = handler.handle(context);
                    if (result.handled()) {
                        return IntentRouteResult.handled(result.answer(), context.intent().id(), 1.0);
                    }
                    return IntentRouteResult.passToModel(result.metadata(), context.intent().id(), 1.0);
                })
                .orElseGet(() -> IntentRouteResult.handled("系统意图【" + context.intent().name() + "】没有可用处理器。", context.intent().id(), 1.0));
    }

    /**
     * 按固定优先级匹配意图：附件默认、精确命令、词法命中、附件问答兜底、embedding top1。
     */
    private Optional<IntentDefinition> match(String input, Map<String, String> metadata) {
        String text = normalize(input);
        boolean hasAttachmentDocs = hasValue(metadata, "attachmentKnowledgeDocumentIds");
        if (text.isBlank() && hasAttachmentDocs) {
            // 用户只发文件不发文字时，默认按“总结附件文档”处理，复用知识库文档读取链路。
            return findById("document.summary");
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        Optional<IntentDefinition> exact = definitions.stream()
                .filter(definition -> definition.examples().stream().anyMatch(example -> normalize(example).equals(text)))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        // 词法匹配只做强包含，避免“打开工作区”误命中“当前工作区”这类汉字重叠场景。
        Optional<IntentDefinition> lexical = lexicalMatch(text);
        if (lexical.isPresent()) {
            return lexical;
        }
        if (hasAttachmentDocs) {
            // 附件 + 任意问题默认走文档问答；显式“总结这个文档”会在上面的词法匹配阶段先命中 document.summary。
            return findById("document.qa");
        }
        return embeddingMatch(input);
    }

    /**
     * 词法匹配只处理强包含关系，优先覆盖 /clear 这类确定性命令。
     */
    private Optional<IntentDefinition> lexicalMatch(String normalizedInput) {
        return definitions.stream()
                .map(definition -> Map.entry(definition, lexicalScore(normalizedInput, definition)))
                .filter(entry -> entry.getValue() >= entry.getKey().threshold())
                .sorted(Map.Entry.<IntentDefinition, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private double lexicalScore(String normalizedInput, IntentDefinition definition) {
        double best = 0;
        for (String negative : definition.negativeExamples()) {
            String normalizedNegative = normalize(negative);
            if (!normalizedNegative.isBlank() && (normalizedInput.contains(normalizedNegative) || normalizedNegative.contains(normalizedInput))) {
                // 反例优先级高于正例，防止“/clear 是什么意思”被误当成执行清理。
                return 0;
            }
        }
        for (String example : definition.examples()) {
            String normalizedExample = normalize(example);
            if (normalizedExample.isBlank()) {
                continue;
            }
            if (normalizedInput.contains(normalizedExample) || normalizedExample.contains(normalizedInput)) {
                best = Math.max(best, 0.95);
            }
        }
        return best;
    }

    private double tokenOverlap(String left, String right) {
        int common = 0;
        for (int i = 0; i < right.length(); i++) {
            if (left.indexOf(right.charAt(i)) >= 0) {
                common++;
            }
        }
        return right.isEmpty() ? 0 : Math.min(0.8, common / (double) right.length());
    }

    /**
     * 语义匹配只计算用户输入向量，并和启动时缓存的样例向量比较。
     * 命中规则已经简化为 top1 分数达到该意图 threshold，negativeExamples 负责拦截误触发。
     */
    private Optional<IntentDefinition> embeddingMatch(String input) {
        if (embeddingClient == null || embeddingOptions == null || vectorIndex.isEmpty()) {
            return Optional.empty();
        }
        try {
            // 语义泛化交给 embedding 处理；请求期只计算用户输入，样例向量已经在服务启动时缓存。
            EmbeddingResult query = embeddingClient.embed(input, embeddingOptions);
            List<ScoredIntent> scored = new java.util.ArrayList<>();
            for (IntentDefinition definition : definitions) {
                double bestExample = vectorIndex.bestExampleScore(query.vector(), definition);
                double bestNegative = vectorIndex.bestNegativeScore(query.vector(), definition);
                if (bestNegative >= definition.threshold() && bestNegative >= bestExample) {
                    continue;
                }
                scored.add(new ScoredIntent(definition, bestExample));
            }
            scored.sort(Comparator.comparingDouble(ScoredIntent::score).reversed());
            if (scored.isEmpty()) {
                return Optional.empty();
            }
            ScoredIntent first = scored.get(0);
            // 只按 top1 + threshold 判定；反例已在上方拦截，不再用 margin 做二义性判断。
            if (first.score() >= first.definition().threshold()) {
                return Optional.of(first.definition());
            }
        } catch (Exception e) {
            log.debug("intent embedding match skipped error={}", e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<IntentDefinition> findById(String id) {
        return definitions.stream().filter(definition -> definition.id().equals(id)).findFirst();
    }

    private boolean hasValue(Map<String, String> metadata, String key) {
        return metadata != null && metadata.get(key) != null && !metadata.get(key).isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record ScoredIntent(IntentDefinition definition, double score) {
    }
}
