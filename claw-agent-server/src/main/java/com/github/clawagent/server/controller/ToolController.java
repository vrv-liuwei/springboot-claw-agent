package com.github.clawagent.server.controller;

import com.github.clawagent.spi.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具定义查询接口。
 */
@RestController
@RequestMapping("/api/v1")
public class ToolController {
    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final AgentToolRegistry toolRegistry;

    public ToolController(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 返回当前 ToolRegistry 中已注册的工具定义。
     */
    @GetMapping("/tools")
    public Object tools() {
        log.debug("tool definitions requested");
        return toolRegistry.definitions();
    }
}
