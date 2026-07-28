package com.github.clawagent.intent;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的待确认动作服务。
 * <p>
 * 用同一套机制承载工具审批、系统意图确认和计划确认，确认范围按 sessionId/channelId/userId 隔离。
 */
public class InMemoryPendingActionService implements PendingActionService {
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();
    private final Map<String, PendingActionExecutor> executors = new ConcurrentHashMap<>();

    @Override
    /**
     * 创建一个待确认动作，并保存确认后的实际执行回调。
     */
    public PendingAction create(PendingActionCreateRequest request, PendingActionExecutor executor) {
        Instant now = Instant.now();
        // 高风险操作要求用户复述完整确认文本；中风险只需要“确认执行”，降低 IM 交互成本。
        String confirmText = request.risk() == IntentRisk.HIGH
                ? "确认执行：" + safeTitle(request.title())
                : "确认执行";
        PendingAction action = new PendingAction(
                UUID.randomUUID().toString(),
                request.type(),
                PendingActionStatus.PENDING,
                safeTitle(request.title()),
                stringValue(request.description()),
                request.risk(),
                confirmText,
                stringValue(request.sessionId()),
                stringValue(request.channelId()),
                stringValue(request.userId()),
                stringValue(request.taskId()),
                stringValue(request.stepId()),
                stringValue(request.targetId()),
                request.metadata(),
                now,
                now.plus(request.ttl()));
        actions.put(action.actionId(), action);
        if (executor != null) {
            // executor 只保存在内存态，用于把“确认动作”和实际执行业务解耦。
            executors.put(action.actionId(), executor);
        }
        return action;
    }

    @Override
    /**
     * 查找当前会话、通道、用户下最近创建的待确认动作。
     */
    public Optional<PendingAction> findPending(String sessionId, String channelId, String userId) {
        // 每次查询前顺手过期，避免用户隔很久回复“确认执行”触发旧动作。
        expireOldActions();
        return actions.values().stream()
                .filter(action -> action.status() == PendingActionStatus.PENDING)
                .filter(action -> eq(action.sessionId(), sessionId) && eq(action.channelId(), channelId) && eq(action.userId(), userId))
                .max(Comparator.comparing(PendingAction::createdAt));
    }

    @Override
    /**
     * 处理 IM 或后台输入的“确认执行/取消执行”文本。
     */
    public PendingActionResult handleUserInput(String sessionId, String channelId, String userId, String input) {
        String normalized = normalize(input);
        if (!isConfirmText(normalized) && !isRejectText(normalized)) {
            return PendingActionResult.none();
        }
        // 待确认动作按 session/channel/user 三元组隔离，避免群聊或多通道串确认。
        Optional<PendingAction> pending = findPending(sessionId, channelId, userId);
        if (pending.isEmpty()) {
            return new PendingActionResult(true, null, "当前没有待确认的操作。");
        }
        PendingAction action = pending.get();
        if (isRejectText(normalized)) {
            return reject(action, input);
        }
        if (!confirmMatches(action, input)) {
            return new PendingActionResult(true, action, "确认内容不完整，请回复：" + action.confirmText());
        }
        return confirm(action, input);
    }

    @Override
    /**
     * 后台按钮已携带 task/step/target 时，直接按目标确认指定动作。
     */
    public PendingActionResult confirmByTarget(PendingActionType type, String taskId, String stepId, String targetId, String input) {
        // Web API 或 Runtime 已知道目标 ID 时，直接按目标确认，不依赖用户文本所在会话。
        return findByTarget(type, taskId, stepId, targetId)
                .map(action -> confirm(action, input))
                .orElseGet(() -> PendingActionResult.none());
    }

    @Override
    /**
     * 后台按钮已携带 task/step/target 时，直接按目标拒绝指定动作。
     */
    public PendingActionResult rejectByTarget(PendingActionType type, String taskId, String stepId, String targetId, String reason) {
        return findByTarget(type, taskId, stepId, targetId)
                .map(action -> reject(action, reason))
                .orElseGet(() -> PendingActionResult.none());
    }

    private Optional<PendingAction> findByTarget(PendingActionType type, String taskId, String stepId, String targetId) {
        expireOldActions();
        return actions.values().stream()
                .filter(action -> action.status() == PendingActionStatus.PENDING)
                .filter(action -> action.type() == type)
                .filter(action -> eq(action.taskId(), taskId) && eq(action.stepId(), stepId) && eq(action.targetId(), targetId))
                .findFirst();
    }

    private PendingActionResult confirm(PendingAction action, String input) {
        PendingAction confirmed = action.withStatus(PendingActionStatus.CONFIRMED);
        actions.put(action.actionId(), confirmed);
        PendingActionExecutor executor = executors.remove(action.actionId());
        String answer = executor == null ? "已确认执行。" : executor.confirm(action, input);
        return new PendingActionResult(true, confirmed, answer == null || answer.isBlank() ? "已确认执行。" : answer);
    }

    private PendingActionResult reject(PendingAction action, String reason) {
        PendingAction rejected = action.withStatus(PendingActionStatus.REJECTED);
        actions.put(action.actionId(), rejected);
        PendingActionExecutor executor = executors.remove(action.actionId());
        String answer = executor == null ? "已取消执行。" : executor.reject(action, reason);
        return new PendingActionResult(true, rejected, answer == null || answer.isBlank() ? "已取消执行。" : answer);
    }

    private void expireOldActions() {
        Instant now = Instant.now();
        actions.replaceAll((id, action) -> action.status() == PendingActionStatus.PENDING
                && action.expiresAt() != null
                && action.expiresAt().isBefore(now)
                ? action.withStatus(PendingActionStatus.EXPIRED)
                : action);
    }

    private boolean confirmMatches(PendingAction action, String input) {
        if (action.risk() == IntentRisk.HIGH) {
            // 高风险确认必须完全匹配，避免用户随手一句“确认执行”放行危险工具。
            return normalize(input).equals(normalize(action.confirmText()));
        }
        return normalize(input).equals("确认执行");
    }

    private boolean isConfirmText(String text) {
        return text.equals("确认执行") || text.startsWith("确认执行：") || text.startsWith("确认执行:");
    }

    private boolean isRejectText(String text) {
        return text.equals("取消") || text.equals("取消执行") || text.equals("不执行");
    }

    private String normalize(String value) {
        return stringValue(value).replaceAll("\\s+", "");
    }

    private boolean eq(String left, String right) {
        return stringValue(left).equals(stringValue(right));
    }

    private String safeTitle(String value) {
        String text = stringValue(value);
        return text.isBlank() ? "待确认操作" : text;
    }

    private String stringValue(String value) {
        return value == null ? "" : value.trim();
    }
}
