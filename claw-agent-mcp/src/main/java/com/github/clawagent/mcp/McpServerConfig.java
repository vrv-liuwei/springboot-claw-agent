package com.github.clawagent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 MCP Server 配置。
 * 该对象不绑定 RAGFlow，任何符合 MCP 的第三方能力都通过这里注册。
 */
public record McpServerConfig(
        /** MCP Server 唯一标识，同时作为 mcpServers 下的配置 key。 */
        String id,
        /** 展示名称；为空时默认使用 id。 */
        String name,
        /** MCP 传输类型，当前真实连接优先支持 STDIO。 */
        McpTransport transport,
        /** 远程 MCP 地址，对应标准配置里的 url/endpoint。 */
        String endpoint,
        /** STDIO 模式下的本地启动命令，例如 npx、node、python、powershell。 */
        String command,
        /** 传递给 command 的参数列表。 */
        List<String> args,
        /** MCP Server 进程或远程请求使用的环境变量。 */
        Map<String, String> env,
        /** SSE/streamableHttp 远程请求使用的 HTTP Header。 */
        Map<String, String> headers,
        /** STDIO 子进程工作目录。 */
        String cwd,
        /** MCP 请求超时时间，单位秒。 */
        int timeoutSeconds,
        /** 自动批准的 MCP tool 名称或规则，后续接入审批策略时使用。 */
        List<String> autoApprove,
        /** 是否启用该 MCP Server 配置。 */
        boolean enabled) {
    public McpServerConfig {
        // record 构造时做不可变防御拷贝，避免外部修改集合影响注册表状态。
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        cwd = cwd == null ? "" : cwd;
        // 超时配置小于等于 0 时回落到 MCP 客户端通用默认值。
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        autoApprove = autoApprove == null ? List.of() : List.copyOf(autoApprove);
    }
}
