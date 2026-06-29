package com.github.clawagent.server.controller;

import com.github.clawagent.server.service.AgentConsoleService;
import com.github.clawagent.server.dto.DevelopmentTaskSummary;
import com.github.clawagent.server.dto.FileChangeView;
import com.github.clawagent.server.dto.FileReviewView;
import com.github.clawagent.server.dto.OpenTaskFileRequest;
import com.github.clawagent.server.dto.RollbackFileSelectionRequest;
import com.github.clawagent.server.dto.RollbackTaskFileRequest;
import com.github.clawagent.server.dto.TaskAuditView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 任务审查与文件审查入口。
 * 这里保持原有 URL 不变，把任务回放、开发摘要、文件 diff 和 rollback 从通用控制器中拆出。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskReviewController {
    private final AgentConsoleService delegate;

    public TaskReviewController(AgentConsoleService delegate) {
        this.delegate = delegate;
    }

    /**
     * 查询任务产生的文件变更列表；服务端会折叠同一路径的旧版本，只返回最新审查视图。
     */
    @GetMapping("/{taskId}/file-changes")
    public List<FileChangeView> taskFileChanges(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return delegate.taskFileChanges(taskId, limit);
    }

    /**
     * 汇总开发任务结果，包括文件、命令、测试、失败分析、Git 建议和后台进程。
     */
    @GetMapping("/{taskId}/development-summary")
    public DevelopmentTaskSummary developmentSummary(@PathVariable("taskId") String taskId) {
        return delegate.developmentSummary(taskId);
    }

    /**
     * 查询单任务审计轨迹，供用户回放审批、工具调用、恢复点和文件变更。
     */
    @GetMapping("/{taskId}/audit")
    public TaskAuditView taskAudit(@PathVariable("taskId") String taskId) {
        return delegate.taskAudit(taskId);
    }

    /**
     * 读取单个文件变更的修改前后内容，用于管理台 diff 审查。
     */
    @GetMapping("/{taskId}/file-review")
    public FileReviewView fileReview(
            @PathVariable("taskId") String taskId,
            @RequestParam("stepId") String stepId,
            @RequestParam("path") String path,
            @RequestParam(name = "backupPath", required = false) String backupPath) throws IOException {
        return delegate.fileReview(taskId, stepId, path, backupPath);
    }

    /**
     * 打开任务变更文件；后端会先确认该路径确实来自当前任务的文件变更记录。
     */
    @PostMapping("/{taskId}/open-file")
    public Map<String, String> openTaskFile(
            @PathVariable("taskId") String taskId,
            @RequestBody OpenTaskFileRequest request) throws IOException {
        return delegate.openTaskFile(taskId, request);
    }

    /**
     * 回滚当前任务产生的某个文件变更，实际写入仍走 filesystem 工具安全链路。
     */
    @PostMapping("/{taskId}/rollback-file")
    public FileReviewView rollbackTaskFile(
            @PathVariable("taskId") String taskId,
            @RequestBody RollbackTaskFileRequest request) throws IOException {
        return delegate.rollbackTaskFile(taskId, request);
    }

    /**
     * 只回滚 diff 中选中的行段，避免为了一个 hunk 覆盖整个文件。
     */
    @PostMapping("/{taskId}/rollback-file-selection")
    public FileReviewView rollbackTaskFileSelection(
            @PathVariable("taskId") String taskId,
            @RequestBody RollbackFileSelectionRequest request) throws IOException {
        return delegate.rollbackTaskFileSelection(taskId, request);
    }
}
