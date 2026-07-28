package com.github.clawagent.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PlanDraftPlanner 只负责生成可审查计划，不负责执行工具。
 */
@Service
public class PlanDraftPlanner {
    private static final Logger log = LoggerFactory.getLogger(PlanDraftPlanner.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelClient modelClient;
    private final ChatOptions chatOptions;
    private final AgentToolRegistry toolRegistry;

    public PlanDraftPlanner(ModelClient modelClient, ChatOptions chatOptions, AgentToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.chatOptions = chatOptions;
        this.toolRegistry = toolRegistry;
    }

    public PlanDraft createPlan(String sessionId, String input, String mode, Map<String, String> metadata) {
        return generate(sessionId, input, "", null, mode, metadata);
    }

    public PlanDraft revisePlan(PlanDraft current, String feedback) {
        StringBuilder request = new StringBuilder();
        request.append("原始目标：").append(nullToEmpty(current.goal())).append("\n");
        request.append("当前计划：\n").append(toPlanText(current)).append("\n");
        request.append("用户修改意见：").append(nullToEmpty(feedback));
        PlanDraft generated = generate(current.sessionId(), request.toString(), feedback, current, "revise", Map.of());
        return current.nextVersion(generated.title(), generated.goal(), generated.summary(),
                generated.items(), generated.assumptions(), generated.risks(), generated.validation());
    }

    private PlanDraft generate(String sessionId, String input, String feedback, PlanDraft current, String mode, Map<String, String> metadata) {
        String content;
        try {
            content = modelClient.chat(List.of(
                    ChatMessage.system(systemPrompt()),
                    ChatMessage.user(userPrompt(input, feedback, current, mode, metadata))
            ), chatOptions);
        } catch (Exception e) {
            // 请求失败和返回格式错误是两类问题，不能都伪装成同一条前端提示，否则无法定位配置、网络或模型输出问题。
            log.warn("plan model request failed sessionId={} mode={} errorType={} message={}",
                    sessionId, mode, e.getClass().getSimpleName(), e.getMessage());
            return fallbackPlan(sessionId, input, "模型计划请求失败，已按需求类型生成可执行计划。");
        }
        try {
            return parsePlan(sessionId, input, content);
        } catch (Exception e) {
            // 模型 HTTP 已成功但 JSON 不可解析时保留可执行兜底，摘要必须明确是响应内容问题。
            log.warn("plan model response parse failed sessionId={} mode={} errorType={} message={}",
                    sessionId, mode, e.getClass().getSimpleName(), e.getMessage());
            return fallbackPlan(sessionId, input, "模型计划返回无法解析，已按需求类型生成可执行计划。");
        }
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 ClawAgent 的计划模式 Planner，只输出 JSON，不输出解释。\n");
        prompt.append("计划用于用户审查，不要调用工具，不要执行命令，不要修改文件。\n");
        prompt.append("输出格式：{\"title\":\"\",\"goal\":\"\",\"summary\":\"\",\"items\":[{\"title\":\"\",\"description\":\"\",\"expectedTools\":[],\"expectedFileChanges\":[],\"riskLevel\":\"low|medium|high\",\"requiresApproval\":false}],\"assumptions\":[],\"risks\":[],\"validation\":[]}。\n");
        prompt.append("步骤数量建议 4-8 个，步骤必须是可执行任务，标题使用动词+对象，避免只写“确认目标”“执行检查”“验证结果”这类空泛步骤。\n");
        prompt.append("计划要像真实任务拆解：说明范围、关键决策、实施/设计步骤、风险处理和验收方式；不要直接输出最终方案正文。\n");
        prompt.append("如果信息不足，先写入 assumptions/risks，不能用追问代替计划；后续 Ask User 能力尚未实现。\n");
        prompt.append("设计/方案类需求也要拆成任务步骤，例如范围边界、场景模型、架构设计、安全策略、验收交付，而不是只给三步通用流程。\n");
        prompt.append("只能在 expectedTools 中引用下面存在的工具 ID，不能编造工具。\n");
        for (ToolDefinition definition : toolRegistry.definitions()) {
            prompt.append("- ").append(definition.id())
                    .append(" risk=").append(definition.riskLevel())
                    .append(" desc=").append(definition.description())
                    .append("\n");
        }
        prompt.append("如果需要写文件、执行脚本、安装依赖、删除文件、访问敏感路径，riskLevel 必须为 high 且 requiresApproval=true。\n");
        return prompt.toString();
    }

    private String userPrompt(String input, String feedback, PlanDraft current, String mode, Map<String, String> metadata) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("计划模式：").append(nullToEmpty(mode)).append("\n");
        appendExecutionContext(prompt, metadata);
        if (current != null) {
            prompt.append("当前计划版本：").append(current.version()).append("\n");
            prompt.append(toPlanText(current)).append("\n");
        }
        appendTemplateInstruction(prompt, metadata);
        if (feedback != null && !feedback.isBlank()) {
            prompt.append("用户修订意见：").append(feedback).append("\n");
        }
        prompt.append("用户需求：").append(nullToEmpty(input)).append("\n");
        return prompt.toString();
    }

    private void appendTemplateInstruction(StringBuilder prompt, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        String templateId = firstNonBlank(metadata.get("plan.templateId"), "");
        String instruction = firstNonBlank(metadata.get("plan.templateInstruction"), "");
        if (templateId.isBlank() && instruction.isBlank()) {
            return;
        }
        // 计划模板只给模型补充生成约束，不能绕过后续审批、权限和 Todo 执行链路。
        prompt.append("计划模板：").append(templateId).append("\n");
        if (!instruction.isBlank()) {
            prompt.append("模板要求：").append(instruction).append("\n");
        }
    }

    private void appendExecutionContext(StringBuilder prompt, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        String projectPath = firstNonBlank(
                metadata.get("activeProjectPath"),
                metadata.get("projectPath"),
                metadata.get("workspace.projectPath"),
                metadata.get("workspaceRoot"),
                metadata.get("workspace.root"));
        String approvalMode = firstNonBlank(metadata.get("approvalMode"), metadata.get("toolPermissionMode"));
        String knowledgeEnabled = firstNonBlank(metadata.get("knowledge.enabled"), "");
        if (projectPath.isBlank() && approvalMode.isBlank() && knowledgeEnabled.isBlank()) {
            return;
        }
        prompt.append("执行上下文：\n");
        if (!projectPath.isBlank()) {
            prompt.append("- 项目目录：").append(projectPath).append("\n");
        }
        if (!approvalMode.isBlank()) {
            prompt.append("- 工具权限模式：").append(approvalMode).append("\n");
        }
        if ("true".equalsIgnoreCase(knowledgeEnabled)) {
            prompt.append("- 已选择知识库文档，计划应优先利用知识库上下文。\n");
        }
    }

    private PlanDraft parsePlan(String sessionId, String originalInput, String content) throws IOException {
        JsonNode root = objectMapper.readTree(extractJsonObject(stripCodeFence(content)));
        List<PlanItem> items = new ArrayList<>();
        JsonNode itemNodes = root.path("items");
        if (itemNodes.isArray()) {
            int order = 1;
            for (JsonNode node : itemNodes) {
                String riskLevel = normalizeRisk(node.path("riskLevel").asText("low"));
                boolean requiresApproval = node.path("requiresApproval").asBoolean("high".equals(riskLevel));
                int itemOrder = order++;
                items.add(new PlanItem(
                        UUID.randomUUID().toString(),
                        itemOrder,
                        firstNonBlank(node.path("title").asText(""), "步骤 " + itemOrder),
                        node.path("description").asText(""),
                        validToolIds(node.path("expectedTools")),
                        readTextArray(node.path("expectedFileChanges")),
                        riskLevel,
                        requiresApproval || "high".equals(riskLevel)));
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("计划响应未包含有效 items");
        }
        return new PlanDraft(
                UUID.randomUUID().toString(),
                sessionId,
                "",
                "DRAFT",
                null,
                null,
                1,
                firstNonBlank(root.path("title").asText(""), "计划：" + preview(originalInput, 24)),
                firstNonBlank(root.path("goal").asText(""), originalInput),
                root.path("summary").asText(""),
                items,
                readTextArray(root.path("assumptions")),
                readTextArray(root.path("risks")),
                readTextArray(root.path("validation")),
                Instant.now(),
                Instant.now());
    }

    private PlanDraft fallbackPlan(String sessionId, String input, String summary) {
        // 模型不可用时仍要给用户一个像任务计划的拆解；这里按需求关键词生成领域化兜底，避免退回三条空泛步骤。
        List<PlanItem> items = fallbackItems(input);
        return new PlanDraft(UUID.randomUUID().toString(), sessionId, "", "DRAFT", null, null, 1,
                "计划：" + preview(input, 24), input, summary,
                items, List.of("计划会默认进入执行流程；高危工具仍按工具权限单独审批。"), List.of(), List.of("完成后对照计划步骤、Todo 状态和工具结果检查。"), Instant.now(), Instant.now());
    }

    private List<PlanItem> fallbackItems(String input) {
        String text = nullToEmpty(input).toLowerCase();
        if (containsAny(text, "设计", "方案", "架构", "产品", "加密", "im", "通讯", "通信")) {
            return items(
                    item(1, "梳理目标范围和约束", "明确目标用户、使用场景、端到端加密边界、合规和兼容性约束。", "low"),
                    item(2, "拆分核心业务场景", "拆出账号关系、单聊/群聊、消息同步、离线消息、多端登录和密钥生命周期。", "low"),
                    item(3, "设计总体架构和模块", "定义客户端、服务端、中转层、存储、推送、密钥管理和审计边界。", "medium"),
                    item(4, "设计安全机制和风险控制", "覆盖端到端加密、密钥轮换、设备信任、消息重放、防泄漏和异常恢复。", "medium"),
                    item(5, "定义交付物和验收标准", "给出方案文档结构、关键接口、验证清单和后续可落地的开发任务。", "low")
            );
        }
        if (containsAny(text, "修复", "bug", "报错", "失败", "异常", "不能", "无法")) {
            return items(
                    item(1, "复现并记录错误现象", "收集输入、日志、错误堆栈和可复现路径，确认失败边界。", "low"),
                    item(2, "定位根因和影响范围", "搜索相关代码和配置，判断问题属于逻辑、配置、权限、依赖还是环境。", "medium"),
                    item(3, "实施最小修复", "只修改根因相关代码，避免无关重构和扩大改动范围。", "medium"),
                    item(4, "执行回归验证", "运行对应编译、测试或手动验证，记录仍未覆盖的风险。", "medium")
            );
        }
        if (containsAny(text, "联调", "接口", "通道", "飞书", "钉钉", "ddio", "webhook")) {
            return items(
                    item(1, "确认联调目标和环境", "明确服务地址、账号配置、回调地址、鉴权方式和测试数据。", "low"),
                    item(2, "检查配置和接口契约", "核对请求字段、响应格式、签名/Token 和事件类型映射。", "medium"),
                    item(3, "执行端到端联调", "按最小链路发送测试请求或消息，记录成功路径和失败响应。", "medium"),
                    item(4, "核对日志并修正问题", "根据服务端和平台日志定位失败点，必要时调整配置或实现。", "medium"),
                    item(5, "整理验收结论", "输出已通过场景、未覆盖场景和下一步处理建议。", "low")
            );
        }
        return items(
                item(1, "明确任务范围和输入", "确认用户目标、项目目录、权限边界和预期交付物。", "low"),
                item(2, "定位相关代码或资料", "按最短路径读取必要文件、搜索关键实现或检查当前状态。", "low"),
                item(3, "制定并执行改动", "根据定位结果进行最小必要修改或操作，记录关键决策。", "medium"),
                item(4, "验证结果并处理失败", "执行合适的编译、测试或检查；失败时根据错误调整方案。", "medium"),
                item(5, "总结变更和剩余风险", "说明完成内容、验证结果、未覆盖风险和后续建议。", "low")
        );
    }

    private List<PlanItem> items(PlanItem... items) {
        return List.of(items);
    }

    private PlanItem item(int order, String title, String description, String riskLevel) {
        return new PlanItem(UUID.randomUUID().toString(), order, title, description, List.of(), List.of(), riskLevel, false);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> validToolIds(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : readTextArray(node)) {
            if (toolRegistry.find(value).isPresent()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> readTextArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private String extractJsonObject(String content) throws IOException {
        String text = content.trim();
        try {
            objectMapper.readTree(text);
            return text;
        } catch (IOException ignored) {
        }
        String latest = "";
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') {
                continue;
            }
            String candidate = readBalancedJsonCandidate(text, start);
            if (candidate.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(candidate);
            if (node.has("items")) {
                latest = candidate;
            }
        }
        return latest.isBlank() ? text : latest;
    }

    private String readBalancedJsonCandidate(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private String stripCodeFence(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }

    private String normalizeRisk(String value) {
        String risk = value == null ? "" : value.trim().toLowerCase();
        return switch (risk) {
            case "medium", "high" -> risk;
            default -> "low";
        };
    }

    private String toPlanText(PlanDraft plan) {
        StringBuilder builder = new StringBuilder();
        builder.append(plan.title()).append("\n");
        for (PlanItem item : plan.items()) {
            builder.append(item.itemOrder()).append(". ").append(item.title()).append(" - ").append(item.description()).append("\n");
        }
        return builder.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String preview(String text, int limit) {
        String normalized = nullToEmpty(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
