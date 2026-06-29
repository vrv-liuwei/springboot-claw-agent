package com.github.clawagent.server.controller;

import com.github.clawagent.server.service.ProcessManagementService;
import com.github.clawagent.server.service.ProcessManagementService.ProcessLogsView;
import com.github.clawagent.server.service.ProcessManagementService.ProcessView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地后台进程管理接口。
 * 这里只暴露列表、日志和停止操作，进程诊断细节统一委托给 ProcessManagementService。
 */
@RestController
@RequestMapping("/api/v1")
public class ProcessController {
    private final ProcessManagementService processManagementService;

    public ProcessController(ProcessManagementService processManagementService) {
        this.processManagementService = processManagementService;
    }

    @GetMapping("/processes")
    public List<ProcessView> processes(@RequestParam(name = "logChars", defaultValue = "4000") int logChars) {
        return processManagementService.processes(logChars);
    }

    @GetMapping("/processes/{pid}/logs")
    public ProcessLogsView processLogs(
            @PathVariable("pid") long pid,
            @RequestParam(name = "maxChars", defaultValue = "12000") int maxChars) {
        return processManagementService.processLogs(pid, maxChars);
    }

    @PostMapping("/processes/{pid}/stop")
    public ProcessView stopProcess(
            @PathVariable("pid") long pid,
            @RequestBody(required = false) StopProcessRequest request) {
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        return processManagementService.stopProcess(pid, force);
    }

    public record StopProcessRequest(Boolean force) {
    }
}
