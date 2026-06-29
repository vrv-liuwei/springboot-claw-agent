package com.github.clawagent.server.controller;

import com.github.clawagent.server.service.AgentConsoleService;
import com.github.clawagent.server.dto.LocalHealthView;
import com.github.clawagent.server.dto.ModelApiTestRequest;
import com.github.clawagent.server.dto.ModelApiTestResponse;
import com.github.clawagent.server.dto.ModelConfigUpdate;
import com.github.clawagent.server.dto.ModelConfigUpsertRequest;
import com.github.clawagent.server.dto.PolicyConfigUpdate;
import com.github.clawagent.server.dto.RecentProjectRequest;
import com.github.clawagent.server.dto.RuntimeConfigSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地运行配置入口。
 * Controller 只绑定 HTTP 路由，配置合并、健康检查和 YAML 写入仍由主链路服务统一处理。
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {
    private final AgentConsoleService delegate;

    public ConfigController(AgentConsoleService delegate) {
        this.delegate = delegate;
    }

    /**
     * 查询当前运行时配置快照，供管理台配置页和初始化向导展示。
     */
    @GetMapping("/runtime")
    public RuntimeConfigSnapshot runtimeConfig() {
        return delegate.runtimeConfig();
    }

    /**
     * 检查本地行动 Agent 的关键依赖；deep=true 时会额外验证模型连通性。
     */
    @GetMapping("/local/health")
    public LocalHealthView localHealth(@RequestParam(name = "deep", defaultValue = "false") boolean deep) {
        return delegate.localHealth(deep);
    }

    /**
     * 保存用户确认过的最近项目目录，避免刷新或继续任务时丢失工作区上下文。
     */
    @PostMapping("/local/recent-projects")
    public RuntimeConfigSnapshot rememberRecentProject(@RequestBody RecentProjectRequest request) {
        return delegate.rememberRecentProject(request);
    }

    /**
     * 保存模型、本地权限和工作区配置到本地覆盖文件。
     */
    @PutMapping("/model")
    public RuntimeConfigSnapshot saveModelConfig(@RequestBody ModelConfigUpdate update) {
        return delegate.saveModelConfig(update);
    }

    /**
     * 只保存审批和本地权限策略，供能力权限页避免误写模型配置。
     */
    @PutMapping("/policy")
    public RuntimeConfigSnapshot savePolicyConfig(@RequestBody PolicyConfigUpdate update) {
        return delegate.savePolicyConfig(update);
    }

    /**
     * 新增或更新模型池中的单个模型配置。
     */
    @PostMapping("/models")
    public RuntimeConfigSnapshot upsertModelConfig(@RequestBody ModelConfigUpsertRequest request) {
        return delegate.upsertModelConfig(request);
    }

    /**
     * 用页面传入的模型参数做一次短连接测试，不写入正式 Runtime 配置。
     */
    @PostMapping("/model/test")
    public ModelApiTestResponse testModelApi(@RequestBody ModelApiTestRequest request) {
        return delegate.testModelApi(request);
    }
}
