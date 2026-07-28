# springboot-claw-agent

`springboot-claw-agent` 是 `clawagent` 的 Java Harness Agent 框架工程。目标是提供一套可独立运行、可嵌入业务系统、可审计、可恢复、可扩展的企业级 Agent Runtime。

当前版本已完成 M1、M2 主线能力、M3 的 MCP/Skill/toolkit 主链路，以及面向本地行动 Agent 的计划模式和任务执行闭环：可以编译、启动独立 Spring Boot 服务，使用 SQLite3 保存会话、消息、任务、步骤、事件、计划草稿和本地记忆，长期记忆支持 SQLite FTS5 + JVector + RRF 混合检索，并通过 OpenAI 兼容接口接入真实大模型，同时提供 Web Console、管理台、桌面 App 静态入口和 REST API。

## 快速启动

推荐使用仓库根目录的一键启动脚本。脚本会检查端口、构建管理台前端、打包服务端，并启动 Spring Boot 服务。

Windows PowerShell：

```powershell
cd D:\workspace\codex\springboot-claw-agent
$env:DEEPSEEK_API_KEY='你的 DeepSeek API Key'
.\start-clawagent.ps1
```

后台启动：

```powershell
.\start-clawagent.ps1 -Background
```

后台启动会等待 Health API 变为 `UP`，默认超时 60 秒；需要调整时使用：

```powershell
.\start-clawagent.ps1 -Background -HealthTimeoutSeconds 120
```

Linux / macOS：

```bash
cd /path/to/springboot-claw-agent
export DEEPSEEK_API_KEY='你的 DeepSeek API Key'
sh ./start-clawagent.sh
```

Linux / macOS 后台启动并等待健康检查：

```bash
BACKGROUND=true HEALTH_TIMEOUT_SECONDS=120 sh ./start-clawagent.sh
```

默认访问：

- Admin Console: `http://localhost:17891/admin/index.html`
- Desktop App Web UI: `http://localhost:17891/app/index.html`
- Web Console: `http://localhost:17891/console/index.html`
- Health API: `http://localhost:17891/api/v1/health`

如果端口被占用，Windows 使用 `.\start-clawagent.ps1 -Port 17892`，Linux / macOS 使用 `PORT=17892 sh ./start-clawagent.sh`。

手动启动方式如下：

```powershell
cd D:\workspace\codex\springboot-claw-agent

$env:JAVA_HOME='D:\tools\Java\64\jdk17.0.7'
$env:Path="$env:JAVA_HOME\bin;D:\tools\Java\apache-maven-3.6.3\bin;$env:Path"
$env:DEEPSEEK_API_KEY='你的 DeepSeek API Key'

& 'D:\tools\Java\apache-maven-3.6.3\bin\mvn.cmd' clean package -DskipTests
& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar --server.port=17891
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

如果默认端口 `17891` 被占用，可以临时指定其他端口：

```powershell
& 'D:\tools\Java\64\jdk17.0.7\bin\java.exe' `
  -jar .\claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar `
  --server.port=17892
```

### Stop

按端口停止服务：

```powershell
$port=17891
$pid=(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).OwningProcess
if ($pid) {
  Stop-Process -Id $pid
}
```

如果本地验证时使用了其他端口，把 `$port` 改成对应端口。

### Check

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/health'
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
  -Uri 'http://localhost:17891/api/v1/tasks/stream' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

该接口会推送任务事件，例如 `task.started`、`step.started`、`step.finished`，并在模型支持 stream 时推送 `llm.delta` 和 `llm.completed`。

默认访问：

- Web Console: `http://localhost:17891/console/index.html`
- Admin Console: `http://localhost:17891/admin/index.html`
- Desktop App Web UI: `http://localhost:17891/app/index.html`
- Health API: `http://localhost:17891/api/v1/health`
- Assistant API: `http://localhost:17891/api/v1/assistant?query=北京天气和计算2+3*4`
- Session API: `http://localhost:17891/api/v1/sessions`
- Plan API: `http://localhost:17891/api/v1/plans`
- MCP Server API: `http://localhost:17891/api/v1/mcp/servers`

默认数据目录：

```text
.clawagent/
  clawagent.db        # SQLite3 会话、消息、任务、步骤、事件数据
  memory/           # 本地记忆兼容文件
  memory/vectors/   # JVector 长期记忆向量索引
  mcp/mcp.json      # 标准 mcpServers 配置，服务重启后自动加载注册信息
  skills/           # Skill 本地安装目录，每个 Skill 一个子目录
```

## 业务流程

M1 当前执行流程：

```text
用户请求
  -> REST API / Web Console / Admin / App
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

通道入口额外增加了系统意图路由：

```text
IM/通道消息
  -> ChannelRouter
  -> PendingActionService 先处理“确认执行/取消执行”
  -> IntentRoutingService 匹配 system-intents.yml
  -> low 风险直接执行 handler
  -> medium/high 风险创建 PendingAction 等待用户确认
  -> 未命中系统意图时进入 AgentRuntime
```

内置系统意图定义在 `claw-agent-intent/src/main/resources/clawagent/intents/system-intents.yml`。当前覆盖 `/clear`、`/compact`、`/context`、`/status`、命令列表、计划确认、工作区查看、文档列表、附件文档总结/问答和知识库检索。`workspace.open`、`workspace.switch` 不作为默认 IM 意图开放，工作区切换仍走后台入口。

统一确认服务 `PendingActionService` 当前承载三类动作：`INTENT_CONFIRMATION`、`TOOL_APPROVAL`、`PLAN_APPROVAL`。普通中风险操作回复 `确认执行` 即可，高风险操作需要回复完整确认文本。

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
- 计划模式对象：`PlanDraft`、`PlanItem`，用于在执行前保存可审查、可修订、可确认的任务计划。
- 工具调用模型：`ToolCall`、`ToolResult`、`ToolDefinition`。
- 任务状态和步骤类型：`TaskStatus`、`StepStatus`、`StepType`。

它是整个框架的领域层，其他模块都围绕这些对象扩展。

### `claw-agent-spi`

扩展接口模块，不放具体实现。

主要包含：

- Runtime 扩展点：`AgentPlanner`、`AgentResponseGenerator`、`AgentCallback`。
- Runtime 横切扩展点：`AgentRuntimeInterceptor`，用于脱敏、审计字段规范化、输出过滤等前置/后置处理。
- 工具扩展点：`AgentTool`。
- 模型扩展点：`ModelClient`、`EmbeddingClient`、`ChatMessage`、`ChatOptions`、`LlmCallTrace`。
- 会话摘要扩展点：`SessionSummarizer`。
- 持久化扩展点：`TaskStore`、`SessionStore`、`SessionMessageStore`、`AgentEventStore`、`PlanStore`。

业务方后续要替换模型、Planner、工具、存储实现，优先实现这里的 SPI。

### `claw-agent-runtime`

Agent 执行链路模块。

主要包含：

- `AgentRuntime` 和 `DefaultAgentRuntime`。
- 会话自动创建、任务创建、工具规划、工具执行、最终回复生成。
- 会话消息、任务步骤、事件审计写入。
- 会话摘要生成和回写。
- 单次任务事件流回调，供 SSE API 推送任务进度。
- Runtime 拦截器链：事件落库前、SSE 推送前、Runtime 日志写入前会按 `order` 顺序执行拦截器；事件落库后会执行后置回调。
- 默认敏感数据脱敏拦截器：避免 API Key、Token、Password 等进入日志、事件表和前端流式事件。
- Rule 模式兜底实现：`RuleBasedPlanner`、`ToolOutputResponseGenerator`、`SimpleSessionSummarizer`。
- 内存版存储：`InMemoryTaskStore`、`InMemorySessionStore`、`InMemorySessionMessageStore`、`InMemoryAgentEventStore`、`InMemoryPlanStore`。
- 日志链路 MDC：`traceId`、`sessionId`、`taskId`、`userId`、`channelId`。

当前执行链路以单任务 ReAct/Tool Calling 为主；后台进程、checkpoint/resume、任务恢复和只读子 Agent 编排已在本地行动链路中逐步接入。子 Agent 目前支持单个派生、批量派发和并行提交，高危 execute 命令已有独立 worker、并发限流池、输出上限、超时强杀和可选 CPU 时间限制；`process.start` 也已支持通过 worker 隔离启动后台进程并返回 pid，后续继续补自动任务拆分策略和更强的 OS 级资源隔离。

### `claw-agent-toolkit`

工具注册、系统工具初始化和内置工具模块。

主要包含：

- `AgentToolRegistry`：统一管理本地工具、MCP 工具、后续 Skill 工具。
- `ToolkitRegistry`：初始化 toolkit 模块内置的本地 `AgentTool`，并把工具注册到 `AgentToolRegistry`。
- 内置低风险工具：
  - `TimeTool`：返回当前服务器时间。
  - `WeatherTool`：通过 Hutool 请求免 key 天气接口并解析当前天气。
  - `WebFetchTool`：`builtin.web.fetch`，打开 URL，并通过 `format=html/text/markdown/json` 返回不同格式内容。
  - `WebSearchTool`：`builtin.web.search`，通过 `WebSearchProvider` 搜索互联网，第一版内置博查 provider。
  - `filesystem` 工具组：内置读取文本、列目录、搜索文件、文件信息、受控写入，不依赖外部 MCP Server。

计算器工具已移除。Web fetch 工具内置在 Java 进程内，不依赖外部 `uvx`、Python 或 stdio MCP 子进程；默认只允许 `http/https`，并拒绝访问 localhost、内网、链路本地地址，降低 SSRF 风险。

Runtime 不直接依赖具体工具，只通过 `AgentToolRegistry` 查找和执行。`claw-agent-spring-boot-starter` 也不再逐个 `new TimeTool/WeatherTool/WebFetchTool`，只初始化 `ToolkitRegistry`；后续 `claw-agent-toolkit` 新增内置工具时，只需要在 `ToolkitRegistry` 中注册。

Toolkit 统一使用 `tools.<toolId>.enabled/env` 配置。`env` 的含义由具体工具自己解析，和 MCP Server 的 `env` 思路一致：

```yaml
clawagent:
  toolkit:
    enabled: true
    tools:
      content:
        enabled: true
        env:
          # web-fetch/web-search 的本地内容缓存，保存 raw/readable/summary/chunks。
          PATH: ".clawagent/artifacts"
          CHUNK_CHARS: "6000"
          SUMMARY_CHARS: "2400"
          READ_MAX_CHARS: "12000"
      web-fetch:
        enabled: true
        env:
          # 默认禁止访问内网，访问局域网 Git 时优先配置白名单。
          ALLOW_PRIVATE_ADDRESSES: "false"
          ALLOWED_HOSTS: "192.168.6.160"
      web-search:
        enabled: true
        env:
          # PROVIDER 只负责选择内置厂商；每个厂商使用自己的配置前缀。
          PROVIDER: bocha
          BOCHA_API_KEY: ${BOCHA_API_KEY:}
          BOCHA_ENDPOINT: https://api.bochaai.com/v1/web-search
          BOCHA_COUNT: "8"
          BOCHA_FRESHNESS: noLimit
          BOCHA_SUMMARY: "true"
          BOCHA_TIMEOUT_MS: "60000"
          BOCHA_MAX_OUTPUT_CHARS: "12000"
      filesystem:
        enabled: true
        env:
          READONLY: "true"
          ALLOWED_ROOTS: "D:/workspace/codex"
          BLOCKED_PATTERNS: "**/.git/**,**/*.key,**/*.pem,**/.env"
          MAX_READ_BYTES: "1048576"
          MAX_SEARCH_RESULTS: "100"
```

`web-search` 和 `web-fetch` 的职责不同：`web-search` 用来发现网页 URL、标题和摘要；`web-fetch` 用来打开某个具体 URL 并提取正文。两者默认会把长内容写入本地 Content Artifact 缓存，只把摘要、`artifactId` 和 metadata 返回给模型；后续需要原文细节时调用 `builtin.content.read` 读取指定 `artifactId` 的 `summary`、`chunk` 或关键词匹配内容，避免重复请求 URL 或重复搜索。

`WebSearchTool` 只固定 `query` 这个公共参数，其余工具入参由当前 `WebSearchProvider` 暴露并解析。后续接入 Bing、SearXNG 等搜索源时，只新增对应 `Provider + Properties`，不会复用或污染博查的 `BOCHA_*` 配置。

### `claw-agent-persistence-sqlite`

SQLite3 持久化模块，当前默认单机存储实现。

主要包含：

- `SqliteTaskStore`。
- 会话、消息、任务、步骤、事件、计划表初始化。
- `TaskStore`、`SessionStore`、`SessionMessageStore`、`AgentEventStore`、`PlanStore` 的 SQLite 实现。
- `LocalUserStore`、`LocalUserSessionStore`、`ApiTokenStore`、`DeviceStore`、`ChannelUserBindingStore` 的 SQLite 实现，覆盖本地用户、登录会话、API Token、设备配对和 Channel 外部用户绑定。

默认数据文件是 `.clawagent/clawagent.db`。计划模式数据写入 `agent_plan` 表。MySQL、PostgreSQL 是后续扩展模块。

### `claw-agent-memory`

正式本地记忆模块，是当前唯一记忆实现模块。

主要包含：

- `MemoryProvider` 的本地实现 `LocalMemoryProvider`。
- SQLite 表：`memory_item`、`memory_chunk`、`memory_chunk_fts`、`memory_vector`、`memory_hit_log`。
- SQLite FTS5 BM25 + JVector + RRF hybrid search。
- `userId/scope/status` 隔离过滤，只有 `active` 记忆进入模型上下文。
- Markdown 兼容层：保留本地可读文件，便于后续迁移和人工排查。
- Runtime 候选记忆提炼：默认只生成 `pending`，需要管理台审核后才会启用。

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

当前已支持 STDIO MCP 的连接、`tools/list`、`tools/call`；也支持 HTTP/streamableHttp 的基础 JSON-RPC 调用和老版 SSE endpoint/message 长连接。resources/prompts 已提供刷新、查询、resource read 和 prompt get 接口。MCP 配置里的 `${ENV_NAME}` 会优先读取当前 server 的 `env`，再读取进程环境变量；`autoApprove` 支持 `*`、原始工具名、完整 `mcp.<serverId>.<toolName>` 和 `mcp.<serverId>.*` 前缀通配。

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

### `claw-agent-admin`

React + Vite 正式管理台源码模块。

主要包含：

- React 管理台源码：`src/App.tsx`、`src/api.ts`、`src/styles.css`。
- 管理台构建配置：`vite.config.ts`。
- 开发代理：本地 `npm run dev` 时把 `/api` 代理到 `http://localhost:17891`。
- 构建产物输出：`claw-agent-server/src/main/resources/static/admin`，由 Spring Boot 直接通过 `/admin/index.html` 访问。

当前管理台采用运维控制台布局，不复用原生 Web Console。已提供：

- 总览指标：会话、工具、MCP、Skill、Token、异常任务。
- 会话管理：历史会话列表，任务、消息、日志、Todo、Token 详情。
- 计划模式：聊天输入框支持计划模式开关和 `/plan` 斜杠指令；需求会先生成计划卡片并自动进入执行队列，阻塞后可修订、继续或取消。
- 文件审查：任务产生的文件变更会在会话中展示，支持 diff 查看、右键操作和回滚入口。
- 工具能力：工具 ID、风险等级、描述。
- MCP Server：传输方式、连接状态、Endpoint/Command、工具数。
- Skills：Skill ID、启用状态、工具数、描述，并支持启用/禁用已安装 Skill。
- Token 页面：当前会话 Token usage 原始聚合数据。
- 配置页面：查看仓库级 `.clawagent` 配置根，保存模型配置到 `.clawagent/config/clawagent.yml`。保存后需要重启服务生效，API Key 只保存环境变量名。

本地开发：

```powershell
cd D:\workspace\codex\springboot-claw-agent\claw-agent-admin
npm install
npm run dev
```

构建到 Spring Boot 静态目录：

```powershell
cd D:\workspace\codex\springboot-claw-agent\claw-agent-admin
npm run build
```

构建后访问：

```text
http://localhost:17891/admin/index.html
```

原生聊天调试页面 `/console/index.html` 保留，用于快速测试任务流式交互。

### `claw-agent-app`

本地桌面端外壳和桌面 App Web UI 模块。

主要包含：

- Electron 桌面端外壳和 `ui/` React + Vite 前端源码。
- App UI 构建配置：`claw-agent-app/ui/vite.config.ts`。
- 构建产物输出：`claw-agent-server/src/main/resources/static/app`，由 Spring Boot 直接通过 `/app/index.html` 访问。
- 与 Admin 聊天主流程保持一致：会话恢复、工具调用展示、文件审查、计划模式开关、`/plan` 指令、计划卡片修订和执行。

本地构建 App Web UI：

```powershell
cd D:\workspace\codex\springboot-claw-agent\claw-agent-app\ui
npm install
npm run build
```

构建后访问：

```text
http://localhost:17891/app/index.html
```

### `claw-agent-server`

独立运行版 Spring Boot 服务。

主要包含：

- 默认端口 `17891`。
- REST API：
  - health
  - task
  - session
  - plan
  - message
  - event
  - tool
  - MCP
  - Skill
- 静态 Web Console：`/console/index.html`。
- React Admin Console 构建产物：`/admin/index.html`。
- Desktop App Web UI 构建产物：`/app/index.html`。
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
  -Uri 'http://localhost:17891/api/v1/sessions' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询会话：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/sessions?limit=20'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/sessions/{sessionId}'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/sessions/{sessionId}/tasks?limit=20'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/sessions/{sessionId}/messages?limit=100'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/sessions/{sessionId}/token-usage?limit=1000'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/tasks/{taskId}/token-usage'
```

Token usage 聚合来自任务事件中的 `llm.call` 记录，返回 `callCount`、`promptTokens`、`completionTokens`、`totalTokens`，并按 `model` 和 `phase` 做分组。当前只统计 token，费用换算规则属于后续观测增强。

生成并保存会话摘要：

```powershell
Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/sessions/{sessionId}/summary?limit=80' `
  -Method Post
```

摘要会写回 `AgentSession.summary`。模型模式下使用 `LlmSessionSummarizer`，`rule` 模式下使用 `SimpleSessionSummarizer`。

## Plan API

Plan 模式用于“先计划、再执行”：用户需求先生成可审查的 `PlanDraft`，前端允许用户修订、确认或取消；只有确认后的计划才能进入 `/run/stream`，执行时会转换为现有 Todo，并继续复用 Runtime、工具审批、事件、文件审查和任务恢复链路。计划修订会写入 `plan.updated` 事件，记录原版本、新版本、用户反馈摘要、步骤数量变化以及新增/删除/变更的步骤，便于管理台和审计页展示计划差异。

创建计划：

```powershell
$body = @{
  sessionId = 'demo-session'
  input = '为当前项目新增一个健康检查接口，并补充验证'
  mode = 'plan'
  templateId = 'local-dev'
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/plans' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询会话计划：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans?sessionId=demo-session&limit=20'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans/{planId}'
```

查询内置计划模板和最近一次修订差异：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans/templates'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans/{planId}/revision-summary'
```

根据用户反馈修订计划：

```powershell
$body = @{ feedback = '先只改后端接口，不修改前端页面' } | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/plans/{planId}/revise' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

手动审批旧版草稿计划或取消计划：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans/{planId}/approve' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/plans/{planId}/cancel' -Method Post
```

执行计划：

```powershell
$body = @{
  channelId = 'webui'
  userId = 'demo-user'
  metadata = @{
    workspaceId = 'default'
  }
} | ConvertTo-Json -Depth 5

Invoke-WebRequest `
  -Uri 'http://localhost:17891/api/v1/plans/{planId}/run/stream' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

Admin 和 App UI 已接入计划模式开关和 `/plan` 指令。输入 `/plan 你的需求` 会直接创建计划；打开计划模式后，普通发送也会先生成计划草稿。计划模式支持内置模板选择，当前内置 `local-dev`、`review-only`、`bugfix`、`integration`。模板只影响计划生成提示词，不绕过工具审批、权限策略或 Todo 执行链路。计划修订后，Admin/App 计划卡片会展示版本差异摘要；计划执行阻塞时会展示阻塞原因，用户可以修订计划后再继续。

## Agent Orchestration API

多 Agent 编排当前定位为父任务派生只读子 Agent。子任务会继承项目、知识库和附件上下文，但强制 `toolPermissionMode=ask` 并清空父任务高危批准，避免父任务授权横向扩散。

子任务 metadata 会同时记录请求隔离和实际隔离：`agent.isolation.requested`、`agent.isolation.effective`、`agent.isolation.profile`、`agent.isolation.enforcement`、`agent.worker.requested`、`agent.worker.effective`、`agent.worker.configured`、`agent.worker.eligible` 和 `agent.worker.mode`。调用方可以通过 `workerMode=process` 表达独立 worker 意图；未配置 worker 时仍降级为 `read-only` + `tool-guard`。`clawagent.agents.worker.enabled=true` 且提供 `command` 后，子任务会通过 external-process dispatcher 启动外部 worker 进程，stdin 输入任务 JSON，stdout 输出 `CLAW_SUBAGENT_WORKER_RESULT_V1` marker 后跟结果 JSON。

`toolkit.tools.execute.env.WORKER_*` 只控制高危命令执行隔离；`clawagent.agents.worker.*` 才是子 Agent Runtime 进程隔离配置，两者不能混用。

外部子 Agent worker 返回格式：

```text
CLAW_SUBAGENT_WORKER_RESULT_V1
{"answer":"执行总结","status":"COMPLETED","metadata":{"worker.id":"local-1"}}
```

`claw-agent-worker` 同时提供内置适配入口 `com.github.clawagent.worker.ClawAgentSubAgentWorkerMain`。它会读取 server 下发的任务 JSON，把内容通过 stdin 转发给下游 Runtime 命令；如果下游命令已经输出 `CLAW_SUBAGENT_WORKER_RESULT_V1`，则直接透传，否则会把 stdout/stderr 包装成标准结果。

示例配置：

```yaml
clawagent:
  agents:
    worker:
      enabled: true
      mode: external-process
      command: "java"
      args:
        - "-cp"
        - "claw-agent-worker/target/claw-agent-worker-1.0.0-SNAPSHOT.jar"
        - "com.github.clawagent.worker.ClawAgentSubAgentWorkerMain"
        - "--timeoutMs"
        - "300000"
        - "--maxOutputBytes"
        - "1048576"
        - "--"
        - "your-sub-agent-runtime-command"
```

创建单个只读子 Agent：

```powershell
$body = @{
  input = '只审查 controller 层，不修改文件'
  role = 'reviewer'
  workerMode = 'none'
  metadata = @{
    scope = 'controller'
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/agents/{parentTaskId}/subtasks' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

批量派发子 Agent：

```powershell
$body = @{
  parallel = $true
  maxParallelism = 4
  strategy = 'fanout'
  metadata = @{
    batch = 'review'
  }
  tasks = @(
    @{ input = '审查 controller 层'; role = 'reviewer'; metadata = @{ scope = 'controller' } },
    @{ input = '审查 service 层'; role = 'reviewer'; metadata = @{ scope = 'service' } }
  )
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/agents/{parentTaskId}/subtasks/batch' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

从计划派发子 Agent：

```powershell
$body = @{
  planId = '{planId}'
  parallel = $true
  maxParallelism = 4
  strategy = 'plan-items'
  includeHighRisk = $false
  metadata = @{
    reason = 'review-before-run'
  }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/agents/{parentTaskId}/subtasks/from-plan' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

`parallel=true` 时后端默认最多同时派发 4 个子任务，可通过 `maxParallelism` 调整；服务端会把实际并发数裁剪到任务数和硬上限 8，并在响应和审计事件里返回最终 `maxParallelism`。`from-plan` 会把 `PlanDraft.items` 转成只读子 Agent：每个子任务 metadata 会带上 `plan.id`、`plan.version`、`plan.itemId`、`plan.itemOrder` 和步骤风险信息。`includeHighRisk=false` 时会跳过 high 风险或 `requiresApproval=true` 的计划项，适合先做并行审查。

当子 Agent 请求 `workerMode=process` 且 `clawagent.agents.worker.*` 已配置 external-process worker 时，服务端会通过独立进程协议调度子 Agent Runtime。成功或失败都会把 `agent.worker.pid/exitCode/elapsedMs/timeoutMs/maxOutputBytes/stdoutBytes/stderrBytes/stdoutTruncated/stderrTruncated/timedOut/terminated` 等字段写回子任务 metadata，便于管理台、审计和排障确认实际隔离进程与强终止行为。

查询子任务和编排图：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/agents/{parentTaskId}/subtasks?limit=100'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/agents/{rootTaskId}/graph?depth=3'
```

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
- `env`、`headers`、`timeout`、`disabled`、`autoApprove`：环境变量、请求头、超时、禁用状态和自动批准规则。`headers` 和 URL 中的 `${ENV_NAME}` 会优先从当前 MCP Server 的 `env` 读取，再读取 Java 进程环境变量；Windows 系统环境变量如果是在服务启动后才新增，需要重启服务进程后才会进入 `System.getenv()`。

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
  -Uri 'http://localhost:17891/api/v1/mcp/import' `
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
  -Uri 'http://localhost:17891/api/v1/mcp/servers' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询 MCP Server：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem'
```

连接并刷新 MCP tools：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem/connect' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem/tools'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem/resources'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem/prompts'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/tools'
```

读取 MCP Resource：

```powershell
Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/mcp/servers/filesystem/resources/read?uri=file:///D:/workspace/codex/README.md'
```

获取 MCP Prompt：

```powershell
$body = @{ arguments = @{} } | ConvertTo-Json -Depth 5

Invoke-RestMethod `
  -Uri 'http://localhost:17891/api/v1/mcp/servers/demo/prompts/demo-prompt/get' `
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
  -Uri 'http://localhost:17891/api/v1/skills' `
  -Method Post `
  -Body $body `
  -ContentType 'application/json; charset=utf-8'
```

查询和启停：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/skills'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/skills/demo-skill'
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/skills/demo-skill/disable' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17891/api/v1/skills/demo-skill/enable' -Method Post
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
- 已完成独立 server，默认端口 `17891`。
- 已完成最小 Web Console。
- 已新增 React + Vite 管理台源码模块 `claw-agent-admin`，构建后访问 `/admin/index.html`。
- 已新增桌面端 App Web UI 模块 `claw-agent-app/ui`，构建后访问 `/app/index.html`，并可被桌面外壳复用。
- 已完成内置工具：天气、时间、WebFetch、WebSearch、Filesystem。
- 已完成 DeepSeek / OpenAI 兼容真实模型调用。
- 已完成 Spring AI ChatClient 可选适配，默认不强制绑定 Spring AI 依赖。
- 已完成 LLM 工具规划和 LLM 最终回复生成。
- 已完成 ReAct Planner，可通过 `clawagent.model.planner=react` 启用。
- 已完成 LLM 原生 Tool Calling，可通过 `clawagent.model.planner=tool-calling` 启用。
- 已完成任务事件 SSE 输出接口 `/api/v1/tasks/stream`，并支持 OpenAI 兼容模型 token/chunk 级 `llm.delta`。
- 已完成基础 Session 表、SessionStore 和 Session API。
- 已完成会话消息表和会话消息查询 API。
- 已完成会话摘要生成 API。
- 已完成会话/任务 Token usage 聚合查询 API。
- 已完成 Plan 计划模式：`PlanDraft/PlanItem/PlanStore`、SQLite `agent_plan` 持久化、计划生成、修订、确认、取消、执行 SSE、内置计划模板、修订差异摘要 API，以及 Admin/App 计划模式 UI。
- 已完成通用 MCP 注册表、STDIO 连接、HTTP/streamableHttp 基础连接、SSE endpoint/message 连接、MCP tools/resources/prompts 管理、resource read、prompt get、MCP Server 管理 API 和本地配置保存。
- 已完成 Skill 本地安装、启用、禁用、列表查询、本地 manifest 保存、工具自动注册和 document/http/script/java 执行器。
- 已完成工具执行前置拦截点 `ToolExecutionGuard`，并提供默认高危工具审批校验。
- 已完成 Runtime 拦截器 SPI `AgentRuntimeInterceptor`，默认提供敏感数据脱敏拦截器，可通过 Spring Bean 扩展。
- 已完成 EmbeddingClient SPI 和 OpenAI 兼容 Embeddings 客户端。
- 已完成内置 filesystem 工具：读取文本、列目录、搜索文件、文件信息、受控写入。
- 已新增 React + Vite 管理台入口 `/admin/index.html` 和 App Web UI 入口 `/app/index.html`，原 `/console/index.html` 保留。
- 已完成智能体定时任务与自动化基础能力：创建、编辑、启停、固定间隔、Cron、单次执行、立即运行、运行历史、失败重试、退避策略、重试耗尽后暂停、耗时/Token/工具链路聚合和管理台页面。

## 未完成能力

- 智能体定时任务增强：后续只补更细的统计报表和趋势分析。
- 记忆质量治理需要继续增强：当前已支持长期记忆本地入库、FTS5/JVector/RRF 检索、命中记录、候选审核和管理台页面；去重、冲突处理、过期策略仍需增强。
- React + Vite 正式管理台持续优化：已覆盖内置能力/权限、Token 成本、审计、自动化、文件审查、进程、记忆和本地配置；后台新增的业务能力仍必须同步补充管理台页面；原 `/console/index.html` 暂时保留。
- Shell/cmd/PowerShell 工具：查询类命令不审批，删除、覆盖、格式化、脚本执行、依赖安装等破坏性操作必须用户确认；`claw-agent-worker` 已提供高危命令独立 JVM 隔离执行、主服务侧 worker 并发限流、超时强杀、JVM heap 限制、输出字节上限、可选进程树 CPU 时间限制和结构化结果回传，`process.start` 可通过 worker 隔离启动后台进程并由主服务继续托管 pid，后续继续补更强的 OS 级内存/沙箱挂载限制。
- `claw-agent-worker` 默认承接 high risk execute 命令和 `builtin.process.start` 后台启动。开发环境先执行 `mvn -pl claw-agent-worker -DskipTests package` 生成 worker jar，再通过 `clawagent.toolkit.tools.execute.env.WORKER_ENABLED/WORKER_JAR/WORKER_JAVA/WORKER_JVM_MAX_HEAP/WORKER_MAX_OUTPUT_BYTES/WORKER_MAX_CPU_TIME_MS/WORKER_MAX_CONCURRENT/WORKER_ACQUIRE_TIMEOUT_MS/WORKER_TERMINATION_GRACE_MS` 控制是否启用、jar 位置、Java 可执行文件、worker 堆大小、输出上限、CPU 时间上限、并发槽位、槽位等待超时和超时后强终止宽限。这里的 `WORKER_*` 是命令隔离 worker；`clawagent.agents.worker.*` 是子 Agent Runtime 进程配置，两者可复用同一个 jar，但入口类和协议不同。`/api/v1/config/local/health` 会检查 worker jar 解析结果，管理台本地配置页和桌面 App 设置页都能直接看到 worker jar 是否存在、实际解析路径和已检查路径。
- Skill 目录风格和加载逻辑调整：每个 Skill 独立保存到 `.clawagent/skills/<skillId>/`，完整加载 `manifest.json`、`SKILL.md`、`scripts/`、`assets/`、`references/`、`lib/` 等资源。
- MCP 健壮性继续增强：当前已有 timeout、启动隔离、运行态按需重连、HTTP headers/env 占位解析和 `autoApprove` 通配规则；更复杂的断线检测和会话级关闭策略后续补充。
- MCP `autoApprove` 已接入工具风险等级，支持 `*`、工具名、完整 toolId 和前缀通配；通道级审批策略仍在 M4 扩展。
- Prompt Injection Defense、本地审批主链路和轻量级 Channel/User/Device/Agent 策略合并已落地；本地用户角色可通过 `clawagent.auth.role-policies` 配置默认审批模式和工具白名单，Agent 角色可通过 `clawagent.agents.policies` 配置子任务工具边界；`clawagent.rate-limit` 已提供单机固定窗口 HTTP 入口限流，按 Token/User/Device/IP 分桶保护 API 和 Channel 回调；企业级 PermissionPolicy 和组织/用户组矩阵后续补充。
- Plan 模式增强：当前已完成计划确认层、执行转换、内置模板库、后端修订差异摘要、Admin/App 前端差异展示和阻塞状态提示。后续只保留更细的自动重规划策略优化，不另造第二套任务执行引擎。
- 通道 Channel 已有基础媒体缓存、媒体 URL/大小/超时限制、钉钉 downloadCode 专用下载、飞书/钉钉卡片与富文本统一 Markdown 富渲染摘要、飞书/钉钉/DDIO 核心标准事件字段、飞书/钉钉 HTTP/Stream/Bot 标准事件 metadata、飞书/钉钉 Stream attachments metadata、附件存在标记、媒体/富文本数量、下载成功/失败数量、类型/来源/文件名/平台 key/渲染状态等索引字段和 DDIO 媒体附件状态 metadata；事件语义基础映射已通过 `eventSemantic/eventAction` 接入，平台原生卡片全量还原按当前产品节奏暂缓；本地用户、首个 owner 初始化、登录会话、会话列表/撤销、API Token owner/scope/基础接口拦截/权限范围、可配置登录拦截、Channel 外部用户绑定本地用户、设备配对、设备密钥轮换、设备用户绑定和 Token/User/Channel/Device/Agent 权限强制合并基础已接入；企业权限矩阵编辑器和权限策略审计解释增强暂缓；本地用户角色策略模板已支持 `clawagent.auth.role-policies`，用户自身 metadata 优先于角色模板；管理台 Channel 页已提供外部用户绑定列表、绑定和解绑入口；admin/app 前端会把本地 session 带入普通任务、恢复任务和 Plan 执行，并使用当前登录用户作为任务 `userId/localUserId`，`claw-agent-app` 顶部已补本地用户登录/退出入口，设置页已补设备配对、密钥校验和心跳入口，创建会话/任务/Plan 会携带 `deviceId` 进入设备级权限合并；管理台创建 API Token 时会优先绑定当前登录用户；本地 session 已补最小角色门禁：`owner/admin` 可管理 Auth/配置/通道/能力边界，`operator/user` 可执行任务链路，`viewer` 只读，`me/logout` 对所有登录用户开放。
- MySQL / PostgreSQL 持久化实现。
- 企业级 VectorStore Provider 实现。
- 分布式部署和 Redis 同步。

## 配置说明

完整配置见 [docs/CONFIGURATION.md](docs/CONFIGURATION.md)。

运行时脱敏默认启用，普通部署只需要保留：

```yaml
clawagent:
  runtime:
    sanitization:
      enabled: true
```

需要扩展企业级审计、脱敏、过滤规则时，在业务应用中实现并注册 `com.github.clawagent.spi.AgentRuntimeInterceptor` Bean。Runtime 会按 `order()` 顺序执行前置拦截和后置回调。

## 后期计划

后期计划和已完成/未完成标记见 [docs/CLAWAGENT-ROADMAP.md](docs/CLAWAGENT-ROADMAP.md)。

## 配置说明

本地运行、模型、Vision 图片理解、附件入口、DDIO 通道和编译命令已整理到 [CONFIGURATION.md](CONFIGURATION.md)。

重点约定：

- `AgentRequest.input` 只表示用户输入的文字问题。
- 图片、文件和视频统一通过 `metadata.attachments` JSON 字符串传入。
- 默认模型 `vision: true` 时，图片直接进入默认模型；否则才使用 `model.vision-model` 做图片描述 fallback。
- DDIO 公众号回调入口为 `/ddio/message`，通道配置可放在 `clawagent.channels.configs` 或 `.clawagent/channels/channels.json`。
