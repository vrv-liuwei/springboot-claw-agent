package com.github.clawagent.server.intent;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.intent.IntentHandler;
import com.github.clawagent.intent.IntentHandlerResult;
import com.github.clawagent.intent.PendingActionResult;
import com.github.clawagent.intent.PendingActionService;
import com.github.clawagent.intent.PendingActionType;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.server.dto.SessionCommandViews;
import com.github.clawagent.server.service.AppWorkspaceService;
import com.github.clawagent.server.service.PlanService;
import com.github.clawagent.server.service.SessionCommandService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 系统内置意图的 Spring Bean 绑定。
 * system-intents.yml 只负责语义样例和路由声明，真正执行逻辑统一收口到这些 handler。
 */
@Configuration(proxyBeanMethods = false)
public class SystemIntentHandlerConfiguration {

    @Bean("sessionCommand.clearContext")
    IntentHandler clearContextHandler(SessionCommandService sessionCommandService) {
        return context -> {
            // 清上下文只移动模型上下文边界，不物理删除历史消息，保证审计和聊天回放仍可用。
            SessionCommandViews.CommandResponse response = sessionCommandService.clearContext(
                    context.sessionId(),
                    new SessionCommandViews.ClearRequest("intent:" + context.input(), false, false));
            return IntentHandlerResult.handled("已清除当前会话上下文，可以开始新的对话了。影响历史消息数：" + response.affectedMessages());
        };
    }

    @Bean("sessionCommand.compactContext")
    IntentHandler compactContextHandler(SessionCommandService sessionCommandService) {
        return context -> {
            SessionCommandViews.CommandResponse response = sessionCommandService.compactContext(
                    context.sessionId(),
                    new SessionCommandViews.CompactRequest(null, "balanced", 120, true, true, true, true));
            return IntentHandlerResult.handled("已压缩当前会话上下文，后续会优先使用摘要继续对话。摘要长度：" + response.summary().length());
        };
    }

    @Bean("sessionCommand.context")
    IntentHandler contextHandler(SessionCommandService sessionCommandService) {
        return context -> {
            SessionCommandViews.ContextView view = sessionCommandService.context(context.sessionId());
            String answer = "当前上下文：版本 " + view.contextVersion()
                    + "，总消息 " + view.totalMessages()
                    + "，活跃消息 " + view.activeMessages()
                    + "，估算字符 " + view.estimatedContextChars()
                    + "，估算 Token " + view.estimatedContextTokens()
                    + "，历史 LLM Token " + view.tokenUsage().totalTokens() + "。";
            return IntentHandlerResult.handled(answer);
        };
    }

    @Bean("sessionCommand.status")
    IntentHandler statusHandler(SessionCommandService sessionCommandService) {
        return context -> {
            SessionCommandViews.StatusView view = sessionCommandService.status(context.sessionId());
            String task = view.currentTask() == null ? "无" : view.currentTask().id() + " / " + view.currentTask().status();
            String workspace = view.workspace() == null ? "未设置" : view.workspace().root();
            String answer = "当前状态：任务=" + task
                    + "，Todo=" + view.todoOpen() + "/" + view.todoTotal()
                    + "，MCP=" + view.mcpConnectedCount() + "/" + view.mcpServerCount()
                    + "，工具数=" + view.toolCount()
                    + "，工作区=" + workspace + "。";
            return IntentHandlerResult.handled(answer);
        };
    }

    @Bean("commands.help")
    IntentHandler commandsHelpHandler() {
        return context -> IntentHandlerResult.handled("支持的系统操作：/clear 清除上下文；/compact 压缩上下文；/context 查看上下文；/status 查看状态；/plan 生成计划；确认执行 确认待处理操作；/workspace 查看当前工作区；文档列表 查看知识库文档。中高风险操作会要求回复：确认执行。");
    }

    @Bean("workspace.current")
    IntentHandler currentWorkspaceHandler(AppWorkspaceService workspaceService) {
        return context -> {
            // IM 侧只允许查看工作区，不开放切换或打开路径，避免外部通道改变本地文件操作边界。
            Optional<AppWorkspaceService.AppWorkspace> workspace = workspaceService.currentWorkspace();
            if (workspace.isEmpty()) {
                return IntentHandlerResult.handled("当前未设置工作区。出于安全考虑，IM 入口不开放切换工作区，请在后台选择项目目录。");
            }
            AppWorkspaceService.AppWorkspace current = workspace.get();
            return IntentHandlerResult.handled("当前工作区：" + current.name() + "\n" + current.root());
        };
    }

    @Bean("workspace.list")
    IntentHandler listWorkspaceHandler(AppWorkspaceService workspaceService) {
        return context -> {
            List<AppWorkspaceService.AppWorkspace> workspaces = workspaceService.recentWorkspaces();
            if (workspaces.isEmpty()) {
                return IntentHandlerResult.handled("暂无最近工作区。请先在后台选择项目目录。");
            }
            StringBuilder answer = new StringBuilder("最近工作区：");
            for (int i = 0; i < Math.min(10, workspaces.size()); i++) {
                AppWorkspaceService.AppWorkspace item = workspaces.get(i);
                answer.append("\n").append(i + 1).append(". ").append(item.name()).append(" - ").append(item.root());
            }
            answer.append("\nIM 入口只展示，不开放切换。需要切换请使用后台工作区入口。");
            return IntentHandlerResult.handled(answer.toString());
        };
    }

    @Bean("document.list")
    IntentHandler listDocumentHandler(KnowledgeService knowledgeService) {
        return context -> {
            List<KnowledgeDocument> documents = knowledgeService.list(context.userId(), 20);
            if (documents.isEmpty()) {
                return IntentHandlerResult.handled("当前没有可用文档。你可以在后台上传文件，或通过支持附件的 IM 通道发送文件。");
            }
            StringBuilder answer = new StringBuilder("最近文档：");
            for (int i = 0; i < documents.size(); i++) {
                KnowledgeDocument item = documents.get(i);
                answer.append("\n").append(i + 1).append(". ").append(item.name())
                        .append(" [").append(item.kind()).append("] id=").append(item.id());
            }
            return IntentHandlerResult.handled(answer.toString());
        };
    }

    @Bean("document.download")
    IntentHandler downloadDocumentHandler() {
        // 下载动作依赖具体 IM 平台的文件出站能力，当前 handler 只做意图收口和明确提示。
        return context -> IntentHandlerResult.handled("已识别下载文档意图。当前 IM 通道还未统一文件下发协议，请先在后台文档列表下载原文件。后续可按通道能力接入文件出站。");
    }

    @Bean("plan.create")
    IntentHandler createPlanHandler(PlanService planService) {
        return context -> {
            PlanDraft plan = planService.create(context.sessionId(), context.input(), "intent", context.metadata());
            // 计划生成只展示步骤，不再创建额外确认动作；真正执行时仍按工具风险单独审批。
            return IntentHandlerResult.handled(formatPlan(plan) + "\n\n计划已生成，可直接执行。");
        };
    }

    @Bean("plan.approve")
    IntentHandler approvePlanHandler(PlanService planService, PendingActionService pendingActionService) {
        return context -> {
            Optional<PlanDraft> latest = latestPlan(planService, context.sessionId());
            if (latest.isEmpty()) {
                return IntentHandlerResult.handled("当前会话没有可执行的计划。");
            }
            PlanDraft plan = latest.get();
            if ("APPROVED".equalsIgnoreCase(plan.status())) {
                return IntentHandlerResult.handled("计划已自动确认，可直接执行：" + plan.title());
            }
            // 兼容旧版本留下的 DRAFT 计划和待确认动作。
            PendingActionResult pendingResult = pendingActionService.confirmByTarget(PendingActionType.PLAN_APPROVAL, "", "", plan.id(), "确认执行");
            if (pendingResult.handled()) {
                return IntentHandlerResult.handled(pendingResult.answer());
            }
            PlanDraft approved = planService.approve(plan.id());
            return IntentHandlerResult.handled("计划已确认：" + approved.title() + "。你可以在后台点击执行。");
        };
    }

    @Bean("plan.cancel")
    IntentHandler cancelPlanHandler(PlanService planService, PendingActionService pendingActionService) {
        return context -> {
            Optional<PlanDraft> latest = latestPlan(planService, context.sessionId());
            if (latest.isEmpty()) {
                return IntentHandlerResult.handled("当前会话没有可取消的计划。");
            }
            PlanDraft plan = latest.get();
            if ("RUNNING".equalsIgnoreCase(plan.status()) || "DONE".equalsIgnoreCase(plan.status())) {
                return IntentHandlerResult.handled("当前计划已经开始执行或已结束，不能取消：" + plan.title());
            }
            pendingActionService.rejectByTarget(PendingActionType.PLAN_APPROVAL, "", "", plan.id(), "用户取消计划");
            PlanDraft cancelled = planService.cancel(plan.id());
            return IntentHandlerResult.handled("已取消计划：" + cancelled.title());
        };
    }

    @Bean("task.resume")
    IntentHandler resumeTaskHandler(AgentRuntime runtime) {
        return context -> {
            List<AgentTask> tasks = runtime.getSessionTasks(context.sessionId(), 20);
            Optional<AgentTask> resumable = tasks.stream()
                    .filter(task -> task.status() == TaskStatus.CONTINUATION_REQUIRED
                            || task.metadata().containsKey("runtime.resumeTodoId")
                            || task.metadata().containsKey("runtime.resumeCheckpoint"))
                    .max(Comparator.comparing(AgentTask::updatedAt));
            if (resumable.isEmpty()) {
                return IntentHandlerResult.handled("当前会话没有找到可恢复任务。");
            }
            AgentTask task = resumable.get();
            // 恢复任务会启动新的执行流，当前先在 IM 中提示恢复点，实际恢复仍交给后台 SSE 流程。
            return IntentHandlerResult.handled("找到可恢复任务：" + task.id()
                    + "，状态：" + task.status()
                    + "。当前 IM 入口先完成意图识别，恢复执行请在后台任务详情中继续，避免在聊天里误触发长任务。");
        };
    }

    private Optional<PlanDraft> latestDraft(PlanService planService, String sessionId) {
        return planService.list(sessionId, 20).stream()
                .filter(plan -> "DRAFT".equalsIgnoreCase(plan.status()))
                .max(Comparator.comparing(PlanDraft::updatedAt));
    }

    private Optional<PlanDraft> latestPlan(PlanService planService, String sessionId) {
        return planService.list(sessionId, 20).stream()
                .filter(plan -> !"DONE".equalsIgnoreCase(plan.status()))
                .max(Comparator.comparing(PlanDraft::updatedAt));
    }

    private String formatPlan(PlanDraft plan) {
        StringBuilder answer = new StringBuilder();
        answer.append("计划已生成：").append(plan.title()).append("\n");
        if (plan.summary() != null && !plan.summary().isBlank()) {
            answer.append(plan.summary()).append("\n");
        }
        for (PlanItem item : plan.items()) {
            answer.append(item.itemOrder()).append(". ").append(item.title());
            if (item.description() != null && !item.description().isBlank()) {
                answer.append("：").append(item.description());
            }
            answer.append("\n");
        }
        return answer.toString().trim();
    }
}
