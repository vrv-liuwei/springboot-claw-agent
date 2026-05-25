package com.github.clawagent.toolkit;

import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.toolkit.execute.ExecuteCommandTool;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;
import com.github.clawagent.toolkit.filesystem.FilesystemAccess;
import com.github.clawagent.toolkit.filesystem.FilesystemFileInfoTool;
import com.github.clawagent.toolkit.filesystem.FilesystemListDirectoryTool;
import com.github.clawagent.toolkit.filesystem.FilesystemReadTextTool;
import com.github.clawagent.toolkit.filesystem.FilesystemSearchFilesTool;
import com.github.clawagent.toolkit.filesystem.FilesystemToolkitProperties;
import com.github.clawagent.toolkit.filesystem.FilesystemWriteFileTool;
import com.github.clawagent.toolkit.todo.TodoCreatePlanTool;
import com.github.clawagent.toolkit.todo.TodoListTool;
import com.github.clawagent.toolkit.todo.TodoUpdateItemTool;
import com.github.clawagent.toolkit.webfetch.WebFetchClient;
import com.github.clawagent.toolkit.webfetch.WebFetchTool;
import com.github.clawagent.toolkit.webfetch.WebFetchToolkitProperties;

import java.util.List;

/**
 * ToolkitRegistry 负责初始化 claw-agent-toolkit 内置的本地工具。
 * Starter 只创建这个注册器，不再逐个创建 TimeTool、WeatherTool、WebFetchTool。
 */
public class ToolkitRegistry {
    public static final String TOOL_TIME = "time";
    public static final String TOOL_WEATHER = "weather";
    public static final String TOOL_WEB_FETCH = "web-fetch";
    public static final String TOOL_FILESYSTEM = "filesystem";
    public static final String TOOL_EXECUTE = "execute";
    public static final String TOOL_TODO = "todo";

    private final AgentToolRegistry toolRegistry;
    private final ToolkitProperties properties;
    private final TodoStore todoStore;
    private boolean loaded;

    public ToolkitRegistry(AgentToolRegistry toolRegistry) {
        this(toolRegistry, new ToolkitProperties(), null);
    }

    public ToolkitRegistry(AgentToolRegistry toolRegistry, ToolkitProperties properties) {
        this(toolRegistry, properties, null);
    }

    public ToolkitRegistry(AgentToolRegistry toolRegistry, ToolkitProperties properties, TodoStore todoStore) {
        this.toolRegistry = toolRegistry;
        this.properties = properties == null ? new ToolkitProperties() : properties;
        this.todoStore = todoStore;
    }

    /**
     * 注册 toolkit 模块自带的系统工具。
     * 后续 toolkit 新增内置工具时，只改这里，不需要改 spring-boot-starter。
     */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        if (!properties.isEnabled()) {
            loaded = true;
            return;
        }
        // 每个系统工具都通过统一 tools.<id>.enabled 开关控制；未配置时默认启用。
        if (properties.tool(TOOL_TIME).isEnabled()) {
            toolRegistry.registerOrReplace(new TimeTool());
        }
        if (properties.tool(TOOL_WEATHER).isEnabled()) {
            toolRegistry.registerOrReplace(new WeatherTool());
        }
        if (properties.tool(TOOL_WEB_FETCH).isEnabled()) {
            // WebFetchTool 的依赖在 toolkit 内部组装，避免 starter 感知具体工具依赖关系。
            WebFetchToolkitProperties webFetchProperties =
                    WebFetchToolkitProperties.fromEnv(properties.tool(TOOL_WEB_FETCH).getEnv());
            toolRegistry.registerOrReplace(new WebFetchTool(new WebFetchClient(webFetchProperties), webFetchProperties));
        }
        if (properties.tool(TOOL_FILESYSTEM).isEnabled()) {
            FilesystemToolkitProperties filesystemProperties =
                    FilesystemToolkitProperties.fromEnv(properties.tool(TOOL_FILESYSTEM).getEnv());
            FilesystemAccess access = new FilesystemAccess(filesystemProperties);
            // filesystem 是 Agent 的基础本地能力，放在 toolkit 内部集中注册，不依赖 MCP 进程是否启动成功。
            List.of(
                    new FilesystemReadTextTool(access),
                    new FilesystemListDirectoryTool(access),
                    new FilesystemWriteFileTool(access),
                    new FilesystemSearchFilesTool(access),
                    new FilesystemFileInfoTool(access)
            ).forEach(toolRegistry::registerOrReplace);
        }
        if (properties.tool(TOOL_EXECUTE).isEnabled()) {
            ExecuteToolkitProperties executeProperties =
                    ExecuteToolkitProperties.fromEnv(properties.tool(TOOL_EXECUTE).getEnv());
            toolRegistry.registerOrReplace(new ExecuteCommandTool(executeProperties));
        }
        if (properties.tool(TOOL_TODO).isEnabled() && todoStore != null) {
            // Todo 工具依赖持久化 store；没有 store 时跳过，避免 toolkit 直接依赖具体数据库实现。
            List.of(
                    new TodoCreatePlanTool(todoStore),
                    new TodoUpdateItemTool(todoStore),
                    new TodoListTool(todoStore)
            ).forEach(toolRegistry::registerOrReplace);
        }
        loaded = true;
    }
}
