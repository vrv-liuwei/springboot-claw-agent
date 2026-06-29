package com.github.clawagent.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务健康检查接口。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {
    /**
     * 返回本地服务最小存活状态，供控制台和部署探针调用。
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "name", "clawagent");
    }
}
