# ClawAgent 配置说明

## 最小配置

默认单机启动只需要：

```yaml
server:
  port: 17890

clawagent:
  persistence:
    type: sqlite
```

如果不写配置，也会使用 `claw-agent-server/src/main/resources/application.yml` 中的默认值。

## 本地覆盖配置

服务启动时会通过 Spring Boot 标准 `spring.config.import` 额外读取本地覆盖 YAML：

```yaml
spring:
  config:
    import:
      - optional:file:.clawagent/config/clawagent.yml
      - optional:file:../.clawagent/config/clawagent.yml
```

这样从仓库根目录启动和从 `claw-agent-server` 子模块启动都能复用同一个仓库级 `.clawagent` 目录。

React 管理台的“配置”页面会把模型配置保存到：

```text
.clawagent/config/clawagent.yml
```

注意：

- 页面只保存本地覆盖 YAML，不直接改打包内置的 `application.yml`。
- 模型客户端、Planner 等 Bean 不做热替换；保存后需要重启服务才会完全生效。
- API Key 字段使用 `api-key`；仓库配置建议写 Spring 占位符，本地覆盖文件可以由页面写入真实密钥。
- 如果从子模块启动，后端会优先查找上级仓库里的 `.clawagent`，避免误写 `claw-agent-server/.clawagent`。

## 当前已生效配置

```yaml
server:
  port: 17890

clawagent:
  enabled: true
  persistence:
    type: sqlite
    sqlite:
      path: .clawagent/clawagent.db
  memory:
    markdown:
      enabled: true
      path: .clawagent/memory
    vector:
      enabled: true
      provider: jvector
      embedding:
        provider: siliconflow
        model: BAAI/bge-m3
        base-url: https://api.siliconflow.cn/v1
        api-key: ${SILICONFLOW_API_KEY}
        dimensions: 0
        timeout-seconds: 60
  mcp:
    enabled: true
    auto-connect: true
    path:
      - .clawagent/mcp/mcp.json
  skills:
    enabled: true
    path:
      - .clawagent/skills
  runtime:
    max-react-rounds: 15
    sanitization:
      enabled: true
  automation:
    enabled: true
    poll-interval-seconds: 5
    due-batch-size: 10
    default-channel-id: automation
    default-user-id: automation
  toolkit:
    enabled: true
    tools:
      time:
        enabled: true
        env: {}
      weather:
        enabled: true
        env: {}
      content:
        enabled: true
        env:
          PATH: ".clawagent/artifacts"
          CHUNK_CHARS: "6000"
          SUMMARY_CHARS: "2400"
          READ_MAX_CHARS: "12000"
      web-fetch:
        enabled: true
        env:
          ALLOW_PRIVATE_ADDRESSES: "false"
          ALLOWED_HOSTS: ""
      web-search:
        enabled: true
        env:
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
  model:
    mode: llm
    client: openai-compatible
    planner: single
    default: deepseek-v4-flash
    # 可选：记忆意图识别使用的模型 ID；为空时复用默认聊天模型。
    memory-model: siliconflow-qwen3-8b
  models:
    deepseek-v4-flash:
      provider: deepseek
      base-url: https://api.deepseek.com
      model: deepseek-v4-flash
      api-key: ${DEEPSEEK_API_KEY}
      temperature: 0.2
      timeout-seconds: 60
    siliconflow-qwen3-8b:
      provider: siliconflow
      base-url: https://api.siliconflow.cn/v1
      model: Qwen/Qwen3-8B
      api-key: ${SILICONFLOW_API_KEY}
      temperature: 0.2
      timeout-seconds: 60
```

说明：

- `server.port`：独立服务端口，默认 `17890`。
- `clawagent.persistence.type`：当前支持 `sqlite`；`mysql`、`postgresql` 是计划项。
- `clawagent.persistence.sqlite.path`：SQLite3 数据库文件路径。
- `clawagent.memory.markdown.path`：本地记忆 Markdown 兼容文件目录，便于人工排查和旧数据迁移。
- `clawagent.memory.vector.enabled`：是否启用本地向量记忆。
- `clawagent.memory.vector.provider`：向量索引提供方，当前本地实现使用 `jvector`。
- `clawagent.memory.vector.path`：JVector 索引落盘目录，默认 `.clawagent/memory/vectors`。
- `clawagent.memory.vector.embedding.*`：Embedding 模型配置，当前复用 OpenAI 兼容 Embeddings 客户端。
- `clawagent.mcp.auto-connect`：是否在服务启动后自动连接已启用 MCP Server，并把 MCP tools 注册到 Agent 工具表，默认 `true`。
- `clawagent.mcp.path`：MCP 标准配置文件列表，支持多个 `mcp.json` 文件，重启后自动加载注册配置。
- `clawagent.skills.path`：Skill 本地安装目录列表，启动时默认扫描并加载每个目录下的 Skill。
- `clawagent.runtime.max-react-rounds`：ReAct Planner 单个任务允许的最大规划/工具执行轮次，防止错误循环无限消耗 token。
- `clawagent.runtime.sanitization.enabled`：是否启用默认敏感数据脱敏拦截器，默认 `true`。
- `clawagent.automation.enabled`：是否启用智能体定时任务调度器，默认 `true`。关闭后仍可保存配置，但不会自动触发。
- `clawagent.automation.poll-interval-seconds`：调度器扫描到期任务的间隔，默认 `5` 秒。
- `clawagent.automation.due-batch-size`：每次最多拉取多少个到期自动化任务，默认 `10`。
- `clawagent.automation.default-channel-id`：自动化触发 Agent 请求时的默认 channel，默认 `automation`。
- `clawagent.automation.default-user-id`：自动化触发 Agent 请求时的默认 user，默认 `automation`。
- `clawagent.toolkit.enabled`：是否启用内置系统工具集合，默认 `true`。
- `clawagent.toolkit.tools.<toolId>.enabled`：是否启用某个内置工具，未配置时默认启用。
- `clawagent.toolkit.tools.<toolId>.env`：工具私有参数，具体含义由对应 `AgentTool` 自己解析。
- `clawagent.toolkit.tools.content.env.PATH`：Content Artifact 本地缓存目录，默认 `.clawagent/artifacts`。
- `clawagent.toolkit.tools.content.env.CHUNK_CHARS`：缓存内容按字符切块大小，默认 `6000`。
- `clawagent.toolkit.tools.content.env.SUMMARY_CHARS`：规则摘要最大字符数，默认 `2400`。
- `clawagent.toolkit.tools.content.env.READ_MAX_CHARS`：`builtin.content.read` 默认最大返回字符数，默认 `12000`。
- `clawagent.toolkit.tools.web-fetch.env.ALLOW_PRIVATE_ADDRESSES`：是否允许访问 `192.168.x.x`、`10.x.x.x`、`172.16-31.x.x` 等私网地址，默认 `false`。
- `clawagent.toolkit.tools.web-fetch.env.ALLOWED_HOSTS`：只放行指定私网 host，多个值用英文逗号或分号分隔，例如 `192.168.6.160,git.example.local`。优先推荐白名单，而不是全局打开私网访问。
- `clawagent.toolkit.tools.web-search.env.PROVIDER`：Web 搜索内置厂商，第一版支持 `bocha`。
- `clawagent.toolkit.tools.web-search.env.BOCHA_*`：博查 Provider 专用配置，包括 `BOCHA_API_KEY`、`BOCHA_ENDPOINT`、`BOCHA_COUNT`、`BOCHA_FRESHNESS`、`BOCHA_SUMMARY`、`BOCHA_TIMEOUT_MS`、`BOCHA_MAX_OUTPUT_CHARS`。后续新增 Bing/SearXNG 时使用各自前缀，不复用博查字段。
- `builtin.web.search` 工具只固定 `query` 公共参数；`count`、`freshness`、`summary`、`timeoutMs` 等由当前 Provider 动态暴露和解析，不作为所有搜索厂商的统一字段。
- `builtin.web.search` 与 `builtin.web.fetch` 默认只向模型返回摘要和 `artifactId`；需要原文片段时调用 `builtin.content.read`，避免 ReAct 重复请求相同 URL 或搜索相同 query。
- `clawagent.toolkit.tools.filesystem.env`：filesystem 当前支持 `READONLY`、`ALLOWED_ROOTS`、`BLOCKED_PATTERNS`、`MAX_READ_BYTES`、`MAX_SEARCH_RESULTS`。
- `clawagent.model.mode`：`llm` 使用真实模型；`rule` 使用本地规则兜底。
- `clawagent.model.client`：模型客户端类型，`openai-compatible` 使用内置 HTTP 客户端；`spring-ai` 使用业务应用提供的 Spring AI `ChatClient.Builder`。
- `clawagent.model.planner`：`single` 使用单轮 JSON 工具规划；`react` 使用多轮 ReAct 规划；`tool-calling` 使用 OpenAI 兼容原生 tools/tool_calls。
- `clawagent.model.default`：默认模型配置 ID。
- `clawagent.model.memory-model`：记忆意图识别模型配置 ID；为空时复用默认聊天模型。
- `clawagent.models.<id>.base-url`：OpenAI 兼容 Chat Completions 根地址。
- `clawagent.models.<id>.api-key`：模型 API Key；仓库示例建议使用 `${ENV_NAME}` 占位符，本地覆盖配置可以保存真实密钥。
- `clawagent.models.<id>.model`：真实模型名。
- SiliconFlow 免费模型可以配置为 `Qwen/Qwen3-8B`；Embedding 可以配置为 `BAAI/bge-m3`。

## 全量目标配置

下面是目标全量配置草案。M1 中只有 SQLite、Markdown、server 端口和模型配置 ID 生效，其余为后续实现目标。

```yaml
server:
  port: 17890

clawagent:
  enabled: true

  instance:
    id: local-1
    mode: standalone # standalone | embedded | cluster
    data-dir: .clawagent

  persistence:
    type: sqlite # sqlite | mysql | postgresql
    sqlite:
      path: .clawagent/clawagent.db
    mysql:
      url: jdbc:mysql://localhost:3306/clawagent
      username: clawagent
      password: ${CLAWAGENT_MYSQL_PASSWORD:}
    postgresql:
      url: jdbc:postgresql://localhost:5432/clawagent
      username: clawagent
      password: ${CLAWAGENT_POSTGRES_PASSWORD:}

  model:
    mode: llm # llm | rule
    client: openai-compatible # openai-compatible | spring-ai
    planner: single # single | react | tool-calling
    default: deepseek-v4-flash

  models:
    deepseek-v4-flash:
      provider: deepseek
      base-url: https://api.deepseek.com
      model: deepseek-v4-flash
      api-key: ${DEEPSEEK_API_KEY}
      temperature: 0.2
      timeout-seconds: 60
    cloud-model:
      provider: openai-compatible
      base-url: https://api.example.com/v1
      api-key: ${CLAWAGENT_MODEL_API_KEY:}
      model: deepseek-chat

  sessions:
    store: database
    retention-days: 30
    persist-tool-observations: true
    persist-model-messages: true
    summarize-on-close: true

  memory:
    markdown:
      enabled: true
      path: .clawagent/memory
      reload-on-change: true
    vector:
      enabled: false
      provider: none # none | in-memory | sqlite | pgvector | qdrant | milvus | opensearch
      embedding:
        provider: none # none | openai-compatible | ollama | internal-http
        model: ""
        base-url: ""
        api-key: ${CLAWAGENT_EMBEDDING_API_KEY:}

  channels:
    defaults:
      auth-required: true
      audit-enabled: true
      allowed-tool-risk-levels:
        - low
        - medium
      high-risk-requires-approval: true
    definitions:
      - id: webui
        type: webui
        enabled: true
        allowed-agent-ids:
          - default-assistant
      - id: public-api
        type: rest-api
        enabled: true
        api-token-required: true
        allowed-tool-risk-levels:
          - low
      - id: embedded-app
        type: embedded
        enabled: true
        trusted-caller: true

  mcp:
    enabled: true
    path:
      - .clawagent/mcp/mcp.json
      - .clawagent/mcp/team.json

  skills:
    enabled: true
    path:
      - .clawagent/skills
      - D:/workspace/codex/shared-skills

  runtime:
    max-react-rounds: 15
    sanitization:
      enabled: true
      replacement: "***"
      sensitive-keys:
        - api_key
        - apikey
        - api-key
        - authorization
        - token
        - secret
        - password
        - key
      value-patterns:
        - "(?i)(api[_-]?key[\"'\\s:=]+)([^\"'\\s,}]+)"
        - "(?i)(authorization[\"'\\s:=]+Bearer\\s+)([^\"'\\s,}]+)"
        - "(?i)(token[\"'\\s:=]+)([^\"'\\s,}]+)"
        - "(?i)(secret[\"'\\s:=]+)([^\"'\\s,}]+)"
        - "(?i)(password[\"'\\s:=]+)([^\"'\\s,}]+)"
        - "as_sk_[A-Za-z0-9_\\-]+"
        - "sk-[A-Za-z0-9_\\-]+"
        - "glpat-[A-Za-z0-9_\\-]+"

  toolkit:
    enabled: true
    tools:
      time:
        enabled: true
        env: {}
      weather:
        enabled: true
        env: {}
      content:
        enabled: true
        env:
          PATH: ".clawagent/artifacts"
          CHUNK_CHARS: "6000"
          SUMMARY_CHARS: "2400"
          READ_MAX_CHARS: "12000"
      web-fetch:
        enabled: true
        env:
          TIMEOUT_MS: "20000"
          MAX_BYTES: "1048576"
          ALLOW_PRIVATE_ADDRESSES: "false"
          ALLOWED_HOSTS: ""
      web-search:
        enabled: true
        env:
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
          ALLOWED_ROOTS: "."
          BLOCKED_PATTERNS: "**/.git/**,**/*.key,**/*.pem,**/.env"
          MAX_READ_BYTES: "1048576"
          MAX_SEARCH_RESULTS: "100"
      shell:
        enabled: false
        env: {}

  security:
    prompt-injection-defense:
      enabled: true
    tool-guard:
      enabled: true
      deny-by-default: true
      require-approval-for-risk-levels:
        - high

  approvals:
    enabled: true

  observability:
    audit:
      enabled: true
      retention-days: 90
    metrics:
      enabled: true
    tracing:
      enabled: false
    cost:
      enabled: true

  distribution:
    enabled: false
```

## 配置原则

- 用户不需要手写全量配置。
- 基础启动配置来自 YAML/env：端口、数据库、数据目录。
- 默认模型客户端是 `openai-compatible`，不强制下载或绑定 Spring AI。业务应用想复用已有 Spring AI 配置时，设置 `clawagent.model.client=spring-ai`，并确保容器里存在 Spring AI `ChatClient.Builder` Bean。
- 当前 MCP Server 默认写入 `clawagent.mcp.path` 的第一个文件，例如 `.clawagent/mcp/mcp.json`；启动时会读取列表中的所有 `mcp.json` 文件。
- Skill 默认写入 `clawagent.skills.path` 的第一个目录，例如 `.clawagent/skills`；启动时会扫描列表中的所有 Skill 目录。
- Runtime 默认启用敏感数据脱敏。普通部署只需要配置 `clawagent.runtime.sanitization.enabled=true`；如需企业自定义规则，可覆盖 `replacement`、`sensitive-keys`、`value-patterns`。
- Toolkit 默认启用，单个工具默认启用；需要禁用时配置 `clawagent.toolkit.tools.<toolId>.enabled=false`。
- Toolkit 工具参数统一放在 `env` 下，starter 只透传，不解释具体含义；新增工具时由工具实现自己解析参数。
- MCP 查询、连接、保存都以文件为准：同名 Server 覆盖，不存在则追加到主 `mcp.json`；测试连接不保存配置。
- 运行时配置后续会逐步迁移或同步到数据库：Agent、Skill、MCP、Tool、Channel、审批策略。
- 高危能力默认关闭，启用后仍需要审批。
- VectorStore 默认关闭，启用时必须配置 EmbeddingClient。

## Runtime 拦截器

`claw-agent-spi` 提供 `AgentRuntimeInterceptor`，用于扩展 Runtime 横切处理。Spring Boot starter 会收集所有 `AgentRuntimeInterceptor` Bean，并按 `order()` 从小到大执行。

当前拦截点：

- `beforeEvent`：`AgentEvent` 持久化前，适合脱敏、字段规范化、审计标签补充。
- `beforeStreamEvent`：SSE/流式事件推送前，适合删除或压缩前端不需要的字段。
- `beforeLogValue`：Runtime 写日志前，适合对 `requestJson`、`responseJson`、工具参数和工具输出做脱敏。
- `afterEvent`：事件持久化后，适合旁路同步、指标统计等后置动作。

默认脱敏拦截器由配置控制：

```yaml
clawagent:
  runtime:
    sanitization:
      enabled: true
```

如需扩展企业规则，业务应用只需要注册 Spring Bean：

```java
@Bean
public AgentRuntimeInterceptor auditTagInterceptor() {
    return new MyAuditTagInterceptor();
}
```

拦截器接口位于 `com.github.clawagent.spi.AgentRuntimeInterceptor`。默认脱敏规则会识别常见字段名 `api_key`、`authorization`、`token`、`secret`、`password`，以及常见密钥值模式 `as_sk_*`、`sk-*`、`glpat-*`。

## MCP 配置文件格式

`clawagent.mcp.path` 指向标准 MCP 客户端配置文件，顶层结构固定为 `mcpServers`：

```json
{
  "mcpServers": {
    "filesystem": {
      "type": "stdio",
      "command": "powershell",
      "args": ["-c", "npx", "-y", "@modelcontextprotocol/server-filesystem", "D:\\workspace\\codex"],
      "env": {},
      "cwd": "D:\\workspace\\codex",
      "timeout": 60,
      "disabled": false
    },
    "windows-mcp": {
      "type": "streamableHttp",
      "url": "http://localhost:3001/mcp",
      "headers": {},
      "autoApprove": ["*", "Type", "Screenshot"]
    }
  }
}
```

字段说明：

- `type` / `transportType`：等价字段，支持 `stdio`、`sse`、`streamableHttp`。
- `command`：`stdio` 必填，本地服务启动命令。
- `args`：`stdio` 参数数组，默认 `[]`。
- `env`：环境变量，支持 `${ENV_NAME}` 从系统环境变量插值。
- `cwd`：`stdio` 子进程工作目录。
- `url`：`sse` / `streamableHttp` 必填，远程 MCP 地址。
- `headers`：远程 MCP 请求头。
- `timeout`：请求超时时间，单位秒，默认 `30`。
- `disabled`：是否禁用该服务，默认 `false`。
- `autoApprove`：工具自动批准规则，支持 `*`、MCP 原始 toolName 或 ClawAgent toolId；命中后该 MCP tool 作为 `low` 风险工具，否则默认 `high` 风险并进入审批链路。

## 工具审批 metadata

当前基础 `ToolExecutionGuard` 已接入 Runtime。低/中风险工具默认放行，高危工具需要在任务请求 metadata 中显式审批：

```json
{
  "input": "执行高危工具",
  "channelId": "webui",
  "userId": "console",
  "metadata": {
    "approvedToolIds": "builtin.shell,skill.demo.run"
  }
}
```

也可以临时允许所有高危工具：

```json
{
  "metadata": {
    "allowHighRiskTools": "true"
  }
}
```

这只是 M3 阶段的最小可用拦截点。正式 Channel、审批流、策略配置仍在 M4 实现。
