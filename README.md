# springboot-claw-agent

`springboot-claw-agent` 是 `clawagent` 的 Java Harness Agent 框架工程。目标是提供一套可独立运行、可嵌入业务系统、可审计、可恢复、可扩展的企业级 Agent Runtime。

当前版本已完成 M1、M2 主线能力和 M3 的 MCP/Skill/toolkit 主链路：可以编译、启动独立 Spring Boot 服务，使用 SQLite3 保存会话、消息、任务、步骤和事件，使用 Markdown 目录作为长期记忆源数据，并通过 OpenAI 兼容接口接入真实大模型，同时提供最小 Web Console 和 REST API。

## 快速启动

```powershell
cd D:\workspace\codex\springboot-claw-agent

$env:JAVA_HOME='D:\tools\Java\64\jdk17.0.7'
$env:Path="$env:JAVA_HOME\bin;D:\tools\Java\apache-maven-3.6.3\bin;$env:Path"
$env:DEEPSEEK_API_KEY='你的 DeepSeek API Key'

& 'D:\tools\Java\apache-maven-3.6.3\bin\mvn.cmd' clean package -DskipTests
& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar
```

## 常用命令

以下命令适用于当前本地 Windows 环境。

### Build

```powershell
cd D:\workspace\codex\springboot-claw-agent

$env:JAVA_HOME='D:\tools\Java\64\jdk17.0.7'
$env:Path="$env:JAVA_HOME\bin;D:\tools\Java\apache-maven-3.6.3\bin;$env:Path"

& 'D:\tools\Java\apache-maven-3.6.3\bin\mvn.cmd' clean package -DskipTests
```

### Start

前台启动，适合开发调试：

```powershell
cd D:\workspace\codex\springboot-claw-agent

$env:DEEPSEEK_API_KEY='你的 DeepSeek API Key'

& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' `
  -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar
```

后台启动，适合本地验证：

```powershell
cd D:\workspace\codex\springboot-claw-agent

$env:DEEPSEEK_API_KEY='你的 DeepSeek API Key'

$out='D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server.out.log'
$err='D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server.err.log'

Start-Process `
  -FilePath 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' `
  -ArgumentList @('-jar','D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar') `
  -WorkingDirectory 'D:\workspace\codex\springboot-claw-agent' `
  -RedirectStandardOutput $out `
  -RedirectStandardError $err `
  -PassThru `
  -WindowStyle Hidden
```

如果默认端口 `17890` 被占用，可以临时指定其他端口：

```powershell
& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' `
  -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar `
  --server.port=17890
```

### Stop

按端口停止服务：

```powershell
$port=17890
$pid=(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess
if ($pid) {
  Stop-Process -Id $pid
}
```

如果本地验证时使用了 `17891`，把 `$port` 改成 `17891`。

### Check

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/health'
```

### Logs

后台启动时日志会写入：

```text
D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server.out.log
D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server.err.log
```

实时查看 INFO 日志：

```powershell
Get-Content -Path D:\workspace\codex\springboot-claw-agent\claw-agent-server\target\claw-agent-server.out.log -Wait -Tail 100
```

临时开启 ClawAgent DEBUG 日志：

```powershell
& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' `
  -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar `
  --logging.level.com.github.clawagent=DEBUG
```

提交任务后，后台会打印这些关键日志：

```text
agent task submit received ...
agent task started ...
llm planner started ...
model chat request ...
model chat response ...
agent planner finished ...
agent tool started ...
agent tool succeeded ...
llm response generation started ...
agent task completed ...
```

SSE 任务事件流接口：

```powershell
$body = @{
  input = '读取当前时间并回答'
  channelId = 'webui'
  userId = 'demo-user'
  metadata = @{}
} | ConvertTo-Json -Depth 5

Invoke-WebRequest `
  -Uri 'http://localhost:17890/api/v1/tasks/stream' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

该接口会推送任务事件，例如 `task.started`、`step.started`、`step.finished`，并在模型支持 stream 时推送 `llm.delta` 和 `llm.completed`。

默认访问：

- Web Console: `http://localhost:17890/console/index.html`
- Health API: `http://localhost:17890/api/v1/health`
- Assistant API: `http://localhost:17890/api/v1/assistant?query=北京天气和计算2+3*4`
- Session API: `http://localhost:17890/api/v1/sessions`
- MCP Server API: `http://localhost:17890/api/v1/mcp/servers`

默认数据目录：

```text
.clawagent/
  clawagent.db        # SQLite3 会话、消息、任务、步骤、事件数据
  memory/           # Markdown 长期记忆
  mcp/mcp.json      # 标准 mcpServers 配置，服务重启后自动加载注册信息
  skills/           # Skill 本地安装目录，每个 Skill 一个子目录
```

## 业务流程

M1 当前执行流程：

```text
用户请求
  -> REST API / Web Console
  -> AgentRuntime 创建或恢复 AgentSession
  -> AgentRuntime 创建 AgentTask
  -> LlmAgentPlanner 调用真实模型生成 ToolCall JSON
  -> AgentToolRegistry 查找工具
  -> 执行 AgentTool
  -> 保存 AgentStep 到 SQLite3
  -> LlmResponseGenerator 调用真实模型生成最终回答
  -> 更新 AgentTask 为 COMPLETED / FAILED
  -> 返回 AgentResult
```

设计目标中的完整流程：

```text
用户请求
  -> Gateway 识别 channel / user / device
  -> Auth 和 Channel Policy 校验
  -> Prompt Injection Defense
  -> Context Builder 加载 session、memory、MCP 上下文
  -> Planner 规划步骤
  -> ToolGuard 校验工具权限和风险
  -> Executor 执行工具或子 Agent
  -> Checkpoint / Audit / Memory 持久化
  -> Verifier 校验最终输出
  -> 返回结果或进入人工审批
```

## 核心模块

### `claw-agent-core`

核心领域模型模块，不依赖 Spring。

主要包含：

- 请求、结果、会话、任务、步骤、事件等对象：`AgentRequest`、`AgentResult`、`AgentSession`、`AgentTask`、`AgentStep`、`AgentEvent`。
- 工具调用模型：`ToolCall`、`ToolResult`、`ToolDefinition`。
- 任务状态和步骤类型：`TaskStatus`、`StepStatus`、`StepType`。

它是整个框架的领域层，其他模块都围绕这些对象扩展。

### `claw-agent-spi`

扩展接口模块，不放具体实现。

主要包含：

- Runtime 扩展点：`AgentPlanner`、`AgentResponseGenerator`、`AgentCallback`。
- 工具扩展点：`AgentTool`。
- 模型扩展点：`ModelClient`、`EmbeddingClient`、`ChatMessage`、`ChatOptions`、`LlmCallTrace`。
- 会话摘要扩展点：`SessionSummarizer`。
- 持久化扩展点：`TaskStore`、`SessionStore`、`SessionMessageStore`、`AgentEventStore`。

业务方后续要替换模型、Planner、工具、存储实现，优先实现这里的 SPI。

### `claw-agent-runtime`

Agent 执行链路模块。

主要包含：

- `AgentRuntime` 和 `DefaultAgentRuntime`。
- 会话自动创建、任务创建、工具规划、工具执行、最终回复生成。
- 会话消息、任务步骤、事件审计写入。
- 会话摘要生成和回写。
- 单次任务事件流回调，供 SSE API 推送任务进度。
- Rule 模式兜底实现：`RuleBasedPlanner`、`ToolOutputResponseGenerator`、`SimpleSessionSummarizer`。
- 内存版存储：`InMemoryTaskStore`、`InMemorySessionStore`、`InMemorySessionMessageStore`、`InMemoryAgentEventStore`。
- 日志链路 MDC：`traceId`、`sessionId`、`taskId`、`userId`、`channelId`。

当前同步执行；异步任务、checkpoint/resume、子 Agent 编排属于后续阶段。

### `claw-agent-toolkit`

工具注册、系统工具初始化和内置工具模块。

主要包含：

- `AgentToolRegistry`：统一管理本地工具、MCP 工具、后续 Skill 工具。
- `ToolkitRegistry`：初始化 toolkit 模块内置的本地 `AgentTool`，并把工具注册到 `AgentToolRegistry`。
- 内置低风险工具：
  - `TimeTool`：返回当前服务器时间。
  - `WeatherTool`：通过 Hutool 请求免 key 天气接口并解析当前天气。
  - `WebFetchTool`：`builtin.web.fetch`，打开 URL，并通过 `format=html/text/markdown/json` 返回不同格式内容。
  - `filesystem` 工具组：内置读取文本、列目录、搜索文件、文件信息、受控写入，不依赖外部 MCP Server。

计算器工具已移除。Web fetch 工具内置在 Java 进程内，不依赖外部 `uvx`、Python 或 stdio MCP 子进程；默认只允许 `http/https`，并拒绝访问 localhost、内网、链路本地地址，降低 SSRF 风险。

Runtime 不直接依赖具体工具，只通过 `AgentToolRegistry` 查找和执行。`claw-agent-spring-boot-starter` 也不再逐个 `new TimeTool/WeatherTool/WebFetchTool`，只初始化 `ToolkitRegistry`；后续 `claw-agent-toolkit` 新增内置工具时，只需要在 `ToolkitRegistry` 中注册。

Toolkit 统一使用 `tools.<toolId>.enabled/env` 配置。`env` 的含义由具体工具自己解析，和 MCP Server 的 `env` 思路一致：

```yaml
clawagent:
  toolkit:
    enabled: true
    tools:
      web-fetch:
        enabled: true
        env:
          # 默认禁止访问内网，访问局域网 Git 时优先配置白名单。
          ALLOW_PRIVATE_ADDRESSES: "false"
          ALLOWED_HOSTS: "192.168.6.160"
      filesystem:
        enabled: true
        env:
          READONLY: "true"
          ALLOWED_ROOTS: "D:/workspace/codex"
          BLOCKED_PATTERNS: "**/.git/**,**/*.key,**/*.pem,**/.env"
          MAX_READ_BYTES: "1048576"
          MAX_SEARCH_RESULTS: "100"
```

### `claw-agent-persistence-sqlite`

SQLite3 持久化模块，当前默认单机存储实现。

主要包含：

- `SqliteTaskStore`。
- 会话、消息、任务、步骤、事件表初始化。
- `TaskStore`、`SessionStore`、`SessionMessageStore`、`AgentEventStore` 的 SQLite 实现。

默认数据文件是 `.clawagent/clawagent.db`。MySQL、PostgreSQL 是后续扩展模块。

### `claw-agent-memory`

记忆能力的父级/占位模块。

当前模块本身不放具体代码，作为后续 Memory SPI、VectorStore、EmbeddingClient 等能力的聚合位置。实际已落地的 Markdown 记忆在 `claw-agent-memory-markdown`。

### `claw-agent-memory-markdown`

Markdown 长期记忆模块。

主要包含：

- `.clawagent/memory` 目录初始化。
- Markdown 文件读取。
- 轻量关键词检索。

当前它更像“可读的本地长期知识目录”。自动记忆提升、会话摘要写入、向量索引还未接入。

### `claw-agent-model-spring-ai`

模型接入模块。

主要包含：

- `OpenAiCompatibleModelClient`：OpenAI 兼容 Chat Completions HTTP 客户端。
- `OpenAiCompatibleEmbeddingClient`：OpenAI 兼容 Embeddings HTTP 客户端。
- `LlmAgentPlanner`：调用真实模型生成工具调用计划。
- `ReActAgentPlanner`：支持多轮“规划 -> 工具 -> 观察 -> 再规划”。
- `ToolCallingAgentPlanner`：支持 OpenAI 兼容 `tools/tool_calls` 原生工具调用。
- `LlmResponseGenerator`：调用真实模型生成最终回复。
- `LlmSessionSummarizer`：调用真实模型生成会话摘要。
- LLM 调用 trace 记录：requestJson、responseJson、token usage。

模块名保留了 Spring AI 方向。当前默认使用轻量 OpenAI-compatible HTTP 客户端；如果业务应用已经引入 Spring AI 并提供 `ChatClient.Builder`，可以设置 `clawagent.model.client=spring-ai` 切换到 Spring AI ChatClient 适配器。

### `claw-agent-mcp`

通用 MCP 接入模块，不绑定 RAGFlow。

主要包含：

- MCP Server 配置模型：`McpServerConfig`。
- MCP 注册表：`McpRegistry`、`FileMcpRegistry`。
- 标准 `mcpServers` JSON 导入：`McpConfigImporter`。
- STDIO MCP 客户端：`StdioMcpClient`。
- HTTP/streamableHttp MCP 客户端：`HttpMcpClient`，通过 JSON-RPC over HTTP 调用远程 MCP 端点。
- SSE MCP 客户端：`SseMcpClient`，兼容老版 MCP SSE endpoint/message 协议。
- MCP tool 到 AgentTool 的适配：`McpAgentTool`。
- MCP resources/prompts 描述：`McpResourceDescriptor`、`McpPromptDescriptor`。
- MCP Server 文件保存：默认写入 `clawagent.mcp.path` 的第一个文件，例如 `.clawagent/mcp/mcp.json`；同名 Server 覆盖，不存在则追加。

当前已支持 STDIO MCP 的连接、`tools/list`、`tools/call`；也支持 HTTP/streamableHttp 的基础 JSON-RPC 调用和老版 SSE endpoint/message 长连接。resources/prompts 已提供刷新、查询、resource read 和 prompt get 接口。

### `claw-agent-skill`

Skill 本地管理模块。

主要包含：

- Skill 元数据：`SkillManifest`。
- Skill 安装请求：`SkillPackage`。
- Skill 注册状态：`SkillRegistration`。
- Skill 注册接口：`SkillRegistry`。
- 文件实现：`FileSkillRegistry`。
- Skill 执行器：`DocumentSkillExecutor`、`HttpSkillExecutor`、`ScriptSkillExecutor`、`JavaSkillExecutor`。

当前支持安装、启用、禁用、查询、列表，并保存到 `.clawagent/skills/<skillId>/manifest.json`。启用的 Skill 会自动注册为 `skill.<skillId>` 或 `skill.<skillId>.<tool>` 工具。执行器已支持 `document`、`http`、`script`、`java` 四类，HTTP/script/java 会校验 manifest 权限声明。

### `claw-agent-observability`

观测能力占位模块。

后续计划承载：

- metrics。
- tracing。
- token/cost 统计。
- audit 查询增强。
- OpenTelemetry / Micrometer 集成。

当前事件审计和日志链路主要在 `claw-agent-runtime` 与 `claw-agent-persistence-sqlite` 中完成。

### `claw-agent-security`

安全治理模块。

当前包含：

- `ToolExecutionGuard` 运行时拦截点。
- `DefaultToolExecutionGuard` 默认策略：高危工具必须通过请求 metadata 显式审批。

后续计划继续扩展 Auth、Channel Access Control、Device Pairing、ApprovalPolicy、Prompt Injection Defense、Shell/file 高危工具隔离执行。

### `claw-agent-spring-boot-starter`

Spring Boot 自动配置模块。

主要包含：

- `ClawAgentProperties` 配置绑定。
- 自动注册模型、Planner、ResponseGenerator。
- 自动注册 Runtime、AgentToolRegistry。
- 自动注册 SQLite/内存存储。
- 自动注册 MarkdownMemoryRepository。
- 自动注册 McpRegistry 和 SkillRegistry。
- 自动发现业务系统里的 `AgentTool` Bean。

业务应用引入这个 starter 后，可以把 ClawAgent 当成内部工具能力使用。

### `claw-agent-server`

独立运行版 Spring Boot 服务。

主要包含：

- 默认端口 `17890`。
- REST API：
  - health
  - task
  - session
  - message
  - event
  - tool
  - MCP
  - Skill
- 静态 Web Console：`/console/index.html`。
- 默认 `application.yml`。

适合像 OpenClaw 一样本地直接启动和配置。

### `claw-agent-samples`

示例模块。

当前是父级/占位模块，用于后续放置：

- starter 嵌入业务应用示例。
- 自定义工具示例。
- MCP Server 配置示例。
- Skill 包示例。
- 企业部署配置示例。

## Starter 引用方式

其他 Spring Boot 应用可以把 ClawAgent 当作内部能力引入：

```xml
<dependency>
    <groupId>com.github.clawagent</groupId>
    <artifactId>claw-agent-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

配置：

```yaml
clawagent:
  persistence:
    type: sqlite
    sqlite:
      path: .clawagent/embedded.db
```

业务代码：

```java
@RestController
public class AssistantController {
    private final AgentRuntime agentRuntime;

    public AssistantController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @GetMapping("/assistant")
    public AgentResult assistant(@RequestParam String query) {
        return agentRuntime.submit(AgentRequest.userMessage(query));
    }
}
```

业务系统也可以声明自己的工具：

```java
@Component
public class CustomerQueryTool implements AgentTool {
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.low("biz.customer-query", "Customer Query", "查询客户信息");
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        return ToolResult.success("客户信息...");
    }
}
```

`claw-agent-spring-boot-starter` 会自动发现所有业务侧 `AgentTool` Bean 并注册到 `AgentToolRegistry`。框架内置工具由 `ToolkitRegistry` 批量注册；MCP 和 Skill 也会在各自 Registry 中把动态工具注册进同一个 `AgentToolRegistry`。

## Session API

M2 已加入基础会话能力。任务提交时如果没有传入 `sessionId`，Runtime 会自动创建会话；如果传入已有 `sessionId`，Runtime 会更新该会话的活跃时间。

创建会话：

```powershell
$body = @{
  title = '测试会话'
  channelId = 'webui'
  userId = 'demo-user'
  metadata = @{}
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/sessions' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询会话：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/sessions?limit=20'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/sessions/{sessionId}'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/sessions/{sessionId}/tasks?limit=20'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/sessions/{sessionId}/messages?limit=100'
```

生成并保存会话摘要：

```powershell
Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/sessions/{sessionId}/summary?limit=80' `
  -Method Post
```

摘要会写回 `AgentSession.summary`。模型模式下使用 `LlmSessionSummarizer`，`rule` 模式下使用 `SimpleSessionSummarizer`。

## MCP API

M3 已加入通用 MCP 接入层。它不是 RAGFlow 专用能力，任何 MCP Server 都可以通过统一配置注册；当前已支持 STDIO MCP 的启动、握手、`tools/list`、`tools/call`，并把 MCP tools 映射为 `AgentTool`。HTTP/streamableHttp 已支持基础 JSON-RPC 调用；SSE 已支持老版 endpoint/message 长连接。

MCP Server 注册配置会保存到：

```text
D:\workspace\codex\springboot-claw-agent\.clawagent\mcp\mcp.json
```

服务重启后会自动从 `clawagent.mcp.path` 配置的所有文件加载 MCP Server。新增或导入的配置默认写入列表中的第一个文件。默认 `clawagent.mcp.auto-connect=true`，服务启动后会自动连接已启用的 STDIO/HTTP/streamableHttp MCP Server，刷新 `tools/list`，并把 MCP tools 注册为 `mcp.<serverId>.<toolName>`。resources/prompts 会作为可选能力刷新，失败不会影响 tools 注册。

Web Console 支持三步操作：

- 直接导入标准 `mcpServers` JSON：可以粘贴 Cherry Studio / Claude Desktop 常见配置。
- 表单注册：选择不同传输模式后，页面只显示该模式需要的字段。`STDIO` 填 `command`、`args`、`env`；`HTTP` / `SSE` / `STREAMABLE_HTTP` 填 `endpoint`、`headers`、`env`。
- 测试连接：只启动临时 MCP Client，不写入 `mcp.json`，也不注册工具；测试通过后再点击“保存 MCP Server”。
- 自动连接：默认启动时自动连接所有 enabled MCP Server；如果不希望服务启动时拉起本地子进程，可以配置 `clawagent.mcp.auto-connect=false`。

保存规则：

- `clawagent.mcp.path` 支持多个文件，查询和连接时会实时读取这些文件。
- 保存时如果 Server name/id 已存在，就覆盖原配置。
- 保存时如果 Server name/id 不存在，就追加到主配置文件，也就是 `clawagent.mcp.path` 的第一个文件。
- 运行态 stdio 子进程、MCP Client 和已注册工具仍保存在当前 JVM 进程中；服务重启后由 `clawagent.mcp.auto-connect` 重新连接。

当前解析兼容通用 MCP 客户端配置字段：

- `type` / `transportType`：传输类型，支持 `stdio`、`sse`、`streamableHttp`。
- `command`、`args`、`cwd`：`stdio` 子进程启动配置。
- `url`：`sse` / `streamableHttp` 远程服务地址。
- `env`、`headers`、`timeout`、`disabled`、`autoApprove`：环境变量、请求头、超时、禁用状态和自动批准规则。

导入标准 `mcpServers` JSON：

```
{
  "mcpServers": {
    "filesystem": {
      "command": "powershell",
      "args": [
        "-c",
        "npx",
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "D:\\workspace\\codex\\"
      ],
      "env": {}
    },
    "fetch": {
      "command": "powershell",
      "args": ["-c","uvx","mcp-server-fetch"]
    }
  }
}
```

```powershell
$body = @{
  json = @'
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "D:\\workspace\\codex"],
      "env": {}
    }
  }
}
'@
} | ConvertTo-Json -Depth 10

Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/mcp/import' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

表单等价的 API 注册 MCP Server：

```powershell
$body = @{
  id = 'filesystem'
  name = 'Filesystem'
  transport = 'STDIO'
  endpoint = $null
  command = 'npx'
  args = @('-y', '@modelcontextprotocol/server-filesystem', 'D:\workspace\codex')
  env = @{}
  headers = @{}
  enabled = $true
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/mcp/servers' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询 MCP Server：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem'
```

连接并刷新 MCP tools：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem/connect' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem/tools'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem/resources'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem/prompts'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/tools'
```

读取 MCP Resource：

```powershell
Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/mcp/servers/filesystem/resources/read?uri=file:///D:/workspace/codex/README.md'
```

获取 MCP Prompt：

```powershell
$body = @{ arguments = @{} } | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/mcp/servers/demo/prompts/demo-prompt/get' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

## Skill API

M3 已加入 `claw-agent-skill` 本地保存和执行能力。当前支持 Skill manifest 安装、启用、禁用、列表查询；Skill 声明的工具会自动加载。执行器已支持 `document`、`http`、`script`、`java` 四类；运行时已接入 `ToolExecutionGuard` 拦截点，高危工具默认需要审批。

### 内置系统 Skill

ClawAgent 启动时会在本地 Skill 目录缺失对应文件时自动生成两个系统 Skill：

```text
.clawagent/skills/skills-create/
  manifest.json
  SKILL.md

.clawagent/skills/skills-install/
  manifest.json
  SKILL.md
```

它们会自动注册为工具：

```text
skill.skills-create
skill.skills-create.create
skill.skills-install
skill.skills-install.install
skill.skills-install.list
```

用途：

- `skills-create`：参考 Codex Skill Creator 的原则，用于创建或更新 ClawAgent Skill，生成 manifest、SKILL.md、references/scripts/assets 结构。
- `skills-install`：参考 Codex Skill Installer 的原则，用于安装、列出、启用、禁用本地 ClawAgent Skill。

如果用户已经修改过同名 Skill，启动引导不会覆盖已有目录。

Skill 会保存到：

```text
D:\workspace\codex\springboot-claw-agent\.clawagent\skills\<skillId>\
  manifest.json
  README.md
```

安装 Skill：

```powershell
$body = @{
  manifest = @{
    id = 'demo-skill'
    name = 'Demo Skill'
    version = '0.1.0'
    description = '本地 Skill 示例'
    enabled = $true
    entrypoint = 'README.md'
    tools = @()
    permissions = @()
    metadata = @{}
  }
  content = '# Demo Skill'
} | ConvertTo-Json -Depth 10

Invoke-RestMethod `
  -Uri 'http://localhost:17890/api/v1/skills' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询和启停：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills/demo-skill'
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills/demo-skill/disable' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills/demo-skill/enable' -Method Post
```

### Skill Executor

没有配置 executor 的 Skill 默认按 `document` 执行：读取 `entrypoint` 文件并返回给 Agent。

可以在 `manifest.metadata.executor` 配置整个 Skill 的默认执行器，也可以在 `manifest.metadata.tools.<toolName>` 为不同工具配置不同执行器。

HTTP Skill 示例：

```json
{
  "id": "demo-http",
  "name": "Demo HTTP",
  "version": "0.1.0",
  "description": "调用一个 HTTP 服务。",
  "enabled": true,
  "entrypoint": "SKILL.md",
  "tools": ["query"],
  "permissions": ["network"],
  "metadata": {
    "tools": {
      "query": {
        "type": "http",
        "method": "GET",
        "url": "https://example.com/search?q=${arg.keyword}",
        "timeoutSeconds": 30,
        "headers": {
          "Accept": "application/json"
        }
      }
    }
  }
}
```

Script Skill 示例：

```json
{
  "id": "demo-script",
  "name": "Demo Script",
  "version": "0.1.0",
  "description": "执行 Skill 目录内 scripts 脚本。",
  "enabled": true,
  "entrypoint": "SKILL.md",
  "tools": ["run"],
  "permissions": ["shell"],
  "metadata": {
    "tools": {
      "run": {
        "type": "script",
        "command": "python",
        "args": ["scripts/demo.py", "${arg.input}"],
        "timeoutSeconds": 30
      }
    }
  }
}
```

Java Skill 示例：

```json
{
  "id": "demo-java",
  "name": "Demo Java",
  "version": "0.1.0",
  "description": "调用 Skill lib 目录里的 Java 插件。",
  "enabled": true,
  "entrypoint": "SKILL.md",
  "tools": ["run"],
  "permissions": ["java"],
  "metadata": {
    "tools": {
      "run": {
        "type": "java",
        "className": "com.example.skill.DemoJavaSkillPlugin",
        "jars": ["lib/demo-java-skill.jar"]
      }
    }
  }
}
```

Java 插件类需要实现 `com.github.clawagent.skill.SkillJavaPlugin`。简单插件只实现 `execute(arguments, config)` 返回文本；复杂插件可以覆盖 `executeTool(call, agentContext, config)` 直接返回 `ToolResult`。

脚本执行默认工作目录是当前 Skill 目录。`command` 如果写成相对路径，必须位于 Skill 目录内；`cwd` 也不能越出 Skill 目录。HTTP 执行器要求 manifest 声明 `network` 或 `http` 权限，脚本执行器要求声明 `shell` 或 `script` 权限，Java 执行器要求声明 `java` 或 `plugin` 权限。Java 插件 Jar 必须位于当前 Skill 目录内，默认扫描 `lib/*.jar`，也可以通过 `jars` 显式指定。

## 当前能力

- 已完成 Maven 多模块工程。
- 已完成核心领域对象、SPI、同步 Runtime。
- 已完成 SQLite3 单机持久化。
- 已完成 Markdown 记忆目录初始化。
- 已完成 Spring Boot starter 自动配置。
- 已完成独立 server，默认端口 `17890`。
- 已完成最小 Web Console。
- 已完成内置工具：天气、时间、WebFetch、Filesystem。
- 已完成 DeepSeek / OpenAI 兼容真实模型调用。
- 已完成 Spring AI ChatClient 可选适配，默认不强制绑定 Spring AI 依赖。
- 已完成 LLM 工具规划和 LLM 最终回复生成。
- 已完成 ReAct Planner，可通过 `clawagent.model.planner=react` 启用。
- 已完成 LLM 原生 Tool Calling，可通过 `clawagent.model.planner=tool-calling` 启用。
- 已完成任务事件 SSE 输出接口 `/api/v1/tasks/stream`，并支持 OpenAI 兼容模型 token/chunk 级 `llm.delta`。
- 已完成基础 Session 表、SessionStore 和 Session API。
- 已完成会话消息表和会话消息查询 API。
- 已完成会话摘要生成 API。
- 已完成通用 MCP 注册表、STDIO 连接、HTTP/streamableHttp 基础连接、SSE endpoint/message 连接、MCP tools/resources/prompts 管理、resource read、prompt get、MCP Server 管理 API 和本地配置保存。
- 已完成 Skill 本地安装、启用、禁用、列表查询、本地 manifest 保存、工具自动注册和 document/http/script/java 执行器。
- 已完成工具执行前置拦截点 `ToolExecutionGuard`，并提供默认高危工具审批校验。
- 已完成 EmbeddingClient SPI 和 OpenAI 兼容 Embeddings 客户端。
- 已完成内置 filesystem 工具：读取文本、列目录、搜索文件、文件信息、受控写入。

## 未完成能力

- Markdown 长期记忆更复杂的去重、合并和质量评估策略。
- MCP 断线重连、超时恢复、会话关闭等健壮性增强。
- MCP `autoApprove` 到正式审批策略的接入。
- 通道 Channel、Auth、设备配对。
- 完整通道级 ToolGuard、审批、限流、Prompt Injection Defense。
- React + Vite 正式管理台。
- MySQL / PostgreSQL 持久化实现。
- VectorStore 实现。
- 分布式部署和 Redis 同步。

## 配置说明

完整配置见 [docs/CONFIGURATION.md](docs/CONFIGURATION.md)。

## 后期计划

后期计划和已完成/未完成标记见 [docs/CLAWAGENT-ROADMAP.md](docs/CLAWAGENT-ROADMAP.md)。
