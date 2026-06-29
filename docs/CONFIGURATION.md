# ClawAgent 配置说明

## 最小配置

默认单机启动只需要：

```yaml
server:
  port: 17891

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

## Channel 接入

Channel 用来承接 WebUI、API、飞书、钉钉等外部入口。当前已拆成独立模块，配置保存到：

```text
.clawagent/channels/channels.json
```

默认内置四个 Channel 模板：

- `webui`：管理台聊天入口。
- `api`：通用 HTTP 接入入口。
- `feishu`：内置飞书官方 HTTP adapter，默认未启用。
- `dingtalk`：内置钉钉自定义机器人 HTTP adapter，默认未启用。

当前模块边界：

- `claw-agent-core`：`ChannelDefinition`、`ChannelInboundMessage`、`ChannelInboundResult`。
- `claw-agent-spi`：`ChannelRegistry`、`ChannelAdapter`。
- `claw-agent-channel`：`FileChannelRegistry`、`ChannelRouter`、`ChannelSessionMapper`、`ChannelRuntimeAdapter`、`ChannelAdapterRegistry`、`ChannelInboundPayloadAdapter`、`ChannelOutboundClient`，负责通用注册、入站路由、外部会话映射和平台委托。飞书平台包位于 `channel.feishu`，已提供 `FeishuInboundAdapter`、`FeishuChannelAdapter`、`FeishuOutboundClient` 和 `FeishuStreamClient`；钉钉平台包位于 `channel.dingtalk`，已提供 `DingtalkInboundAdapter`、`DingtalkChannelAdapter`、`DingtalkOutboundClient` 和 `DingtalkStreamClient`。`ChannelInboundPayloadAdapter`、`ChannelOutboundClient` 和 `ChannelStreamClientManager` 都只查询 `ChannelAdapterRegistry`，不按平台类型写死分支；内置飞书/钉钉/DDIO adapter 与外部 jar adapter 走同一套注册和分发机制。
- `claw-agent-server`：只保留 HTTP Controller，不再保存 Channel 领域对象或注册实现。
- 飞书长连接 SDK 和钉钉 Stream SDK 已放在 `claw-agent-channel` 内置 adapter 下；飞书 Stream 生命周期位于 `channel.feishu.FeishuStreamClient`，钉钉 Stream 生命周期位于 `channel.dingtalk.DingtalkStreamClient`，通用 `ChannelStreamClientManager` 只保留运行表、启停分发和状态返回。后续新增 IM adapter 继续放在 channel 模块或独立 adapter 模块，不要放回 `claw-agent-server`。独立 jar 可以实现 `ChannelRuntimeAdapter` 并通过 `META-INF/services/com.github.clawagent.channel.ChannelRuntimeAdapter` 暴露；启动时会扫描 `clawagent.channels.adapter-path` 指向的 jar 或目录，并把这些 adapter 加入同一注册表。

管理接口：

```http
GET    /api/v1/channels
GET    /api/v1/channels/adapters
POST   /api/v1/channels/adapters/reload
POST   /api/v1/channels/adapters/upload
GET    /api/v1/channels/{channelId}
POST   /api/v1/channels
PUT    /api/v1/channels/{channelId}
DELETE /api/v1/channels/{channelId}
POST   /api/v1/channels/{channelId}/health
POST   /api/v1/channels/{channelId}/outbound/test
GET    /api/v1/channels/{channelId}/stream/status
POST   /api/v1/channels/{channelId}/stream/start
POST   /api/v1/channels/{channelId}/stream/stop
```

统一入站接口：

```http
POST /api/v1/channels/inbound
POST /api/v1/channels/{channelId}/inbound
```

请求体：

```json
{
  "channelId": "api",
  "externalConversationId": "group-or-chat-id",
  "externalUserId": "user-id",
  "messageType": "text",
  "text": "帮我查看项目状态",
  "metadata": {
    "workspaceRoot": "D:\\workspace\\project"
  }
}
```

服务端会把 `channelId + externalConversationId` 映射成稳定 `sessionId`，让同一个外部会话持续复用同一个 Agent 会话。Channel 的 `approvalMode` 和 `approvedToolIds` 会写入任务 metadata，继续复用现有 `ToolExecutionGuard`，不会绕过本地审批链路。

### application.yml 配置 Channel

除了 `.clawagent/channels/channels.json`，也可以在 `application.yml` 或 `.clawagent/config/clawagent.yml` 中声明 Channel。启动时合并顺序为：

```text
内置模板 < .clawagent/channels/channels.json < application.yml / 本地覆盖 YAML
```

也就是说，`application.yml` / `.clawagent/config/clawagent.yml` 是部署侧显式配置，优先级最高；管理台保存后的 `channels.json` 可以覆盖内置模板，但同一个 channelId 遇到 YAML 配置时以 YAML 为准。
YAML 管理的 Channel 会带有 `channel.source=yaml` 和 `channel.readOnly=true` 元数据；管理台会显示 YAML 标识并禁止保存同 ID 覆盖，避免出现“保存成功但被 YAML 覆盖”的假象。

扁平写法适合通用 HTTP 或自定义 IM：

```yaml
clawagent:
  channels:
    definitions:
      - id: workchat
        name: 企业 IM
        type: workchat
        enabled: true
        inbound-enabled: true
        outbound-enabled: true
        approval-mode: ask
        inbound-path: /api/v1/channels/workchat/inbound
        metadata:
          adapter: external
          tenant: default
```

OpenClaw 多账号风格写法放在 `clawagent.channels.configs` 下，key 是 channel type：

```yaml
clawagent:
  channels:
    configs:
      feishu:
        defaultAccount: main
        dmPolicy: open
        accounts:
          main:
            name: 主助手 - 飞书机器人
            enabled: true
            appId: cli_xxx
            appSecret: ${FEISHU_MAIN_APP_SECRET:}
            appIdEnv: FEISHU_MAIN_APP_ID
            appSecretEnv: FEISHU_MAIN_APP_SECRET
          coder:
            name: 代码助手 - 飞书机器人
            enabled: true
            appIdEnv: FEISHU_CODER_APP_ID
            appSecretEnv: FEISHU_CODER_APP_SECRET
```

注意：

- `definitions` 是 Spring Boot 强类型配置，支持 `inbound-enabled` 这类 kebab-case；`configs` 是平台原始配置 Map，建议使用 adapter 识别的 camelCase key，例如 `defaultAccount`、`appId`、`appSecret`、`appIdEnv`、`appSecretEnv`、`webhookUrlEnv`。
- 凭证解析规则是 YAML 直配优先、环境变量兜底：adapter 会先读取 `appId/appSecret/baseUrl/webhookUrl` 这类直接值；直接值为空时，才读取 `appIdEnv/appSecretEnv/baseUrlEnv/webhookUrlEnv` 指向的环境变量。
- 如果同一个账号同时写了 `appSecret` 和 `appSecretEnv`，实际运行会优先使用 `appSecret`。
- 仓库内置 `application.yml` 可以放部署默认值；生产环境建议通过 `.clawagent/config/clawagent.yml` 或环境变量覆盖，避免不同机器共用同一份密钥。
- 飞书/钉钉兼容路径 `/api/v1/channels/feishu/inbound`、`/api/v1/channels/dingtalk/inbound` 会优先路由到启用的 YAML 默认账号，例如 `feishu-main`、`dingtalk-main`；直接使用 `/api/v1/channels/{channelId}/inbound` 仍然按精确 channelId 入站。
- DDIO 兼容入口 `/ddio/message` 不固定绑定内置 `ddio` 占位通道；运行时会优先选择启用的 YAML DDIO 账号，例如 `ddio-main`。

当前边界：

- 统一 Channel API 已可供其他 IM 项目对接。
- 内置 Channel 不是硬编码执行路径：`feishu/dingtalk/ddio` 只是默认模板和默认 adapter，入站、出站、连通性检查和 Stream 启停都会根据 `ChannelDefinition.type` 从 `ChannelAdapterRegistry` 查找实现。
- `GET /api/v1/channels/adapters` 会返回当前进程加载到的 adapter 诊断信息，包括 type、实现类、来源、代码位置和是否为最终生效实现；`POST /api/v1/channels/adapters/reload` 会重新扫描 `clawagent.channels.adapter-path` 指向的外部 jar，普通入站、出站和连通性检查会立即使用新的 adapter 注册表；`POST /api/v1/channels/adapters/upload` 可上传 `.jar` 到第一个 adapter-path 目录并自动重新扫描。已启动的飞书/钉钉 Stream 长连接仍按启动时实例运行，如需切换长连接 adapter，先停止旧 Stream 再重启进程。
- 飞书 HTTP adapter 已支持明文 URL challenge、Verification Token 校验、Encrypt Key 解密、文本消息入站、图片/文件/卡片/富文本占位文本与 metadata、`tenant_access_token` 缓存、`im/v1/messages` 文本回写和 `outboundMessageType=post/markdown/rich_text` 基础 post 回写。
- 钉钉 HTTP adapter 已支持自定义机器人文本入站、配置 Secret 后的入站 timestamp/sign 校验、图片/文件/Markdown/卡片占位文本与 metadata、webhook HMAC-SHA256 加签、文本回写和 `outboundMessageType=markdown` markdown 回写。
- 管理台 Channel 页已提供飞书/钉钉/DDIO 专用配置项。飞书可维护 `appId/appSecret` 或 env、`outboundMessageType=text|post`；钉钉可维护 webhook、Stream `clientId/clientSecret` 或兼容 `appKey/appSecret`、`outboundMessageType=text|markdown` 和 `markdownTitle`；DDIO 可维护 `appId/appSecret/baseUrl` 或 env，并可选择 `channel.ddio.chatScene=user|group`。保存后仍落到 Channel metadata；DDIO 出站会按“入站消息 metadata > Channel metadata > user 默认值”的顺序解析 `channel.ddio.chatScene`，管理台手动测试也能发群消息。页面同时提供“出站测试”，可手动验证飞书 receive_id、钉钉 webhook 或 DDIO `receTargetID` 是否能收到回写消息，并展示平台 HTTP 状态和响应摘要。多账号配置会在列表中展示 `channel.accountId` 和默认账号标识，便于区分 `feishu-main`、`ddio-main` 这类展开后的 Channel。
- `/api/v1/channels/{channelId}/health` 可检查通道配置。飞书会尝试获取 `tenant_access_token`；钉钉只验证 webhook 和签名配置，不主动发送测试消息，避免产生群消息噪声。
- 钉钉如果配置 `connectionMode=stream` / `dingtalk-stream`，health 会检查 `clientId/clientSecret` 或兼容的 `appKey/appSecret`，不会强制要求自定义机器人 `webhookUrl`；真实长连接仍通过 `/api/v1/channels/{channelId}/stream/start` 启动验证。
- DDIO 默认 HTTP 客户端兼容内网自签 HTTPS 地址；单元测试和外部 adapter 可通过注入 `HttpClient` 验证 token 获取和消息发送，不再绕过通用 Channel 出站门面。
- 飞书长连接 SDK 和钉钉 Stream SDK 已有进程内启动/停止/状态接口。飞书长连接由 `FeishuStreamClient` 处理 `P2MessageReceiveV1` 文本消息并转入统一 ChannelRouter；钉钉 Stream 由 `DingtalkStreamClient` 接入通用事件监听，优先抽取 `text.content/conversationId/senderStaffId/msgtype` 等常见字段后转入统一 ChannelRouter。
- 飞书当前 SDK 版本的长连接 `Client` 没有公开 `stop()` 方法，停止接口会移除本地运行记录并提示需要重启进程彻底断开旧连接；钉钉 Stream 支持真实 `stop()`。
- `channels.json` 已支持两种读取格式：历史 `ChannelDefinition[]` 列表，以及 OpenClaw 风格的 `{ "channels": { "<type>": { "defaultAccount": "...", "accounts": ... } } }`。`accounts` 可以是对象或数组；读取时会展开成统一 `ChannelDefinition`，对象格式默认生成 `${type}-${accountId}`，数组格式优先使用 `accountId/id`，`default` 账号会使用 `${type}` 作为 channelId。平台级配置会先写入 metadata，账号级配置覆盖同名 key，并额外记录 `channel.configStyle=accounts`、`channel.accountId`、`channel.defaultAccount` 和 `channel.isDefaultAccount`。
- 当前管理接口保存、删除后仍会把配置规范化写回 `ChannelDefinition[]` 列表，不保留原始 OpenClaw 分组结构；后续管理台如果要做多账号分组编辑，再单独增加分组配置编辑模型。
- 外部 adapter jar 默认放在 `.clawagent/channels/adapters`。每个 jar 需要包含 `META-INF/services/com.github.clawagent.channel.ChannelRuntimeAdapter`，文件内容是实现类全限定名；同 `type()` 的外部 adapter 会覆盖内置实现，便于企业把飞书、钉钉或自有 IM 替换成自己的协议实现。
- 全量事件映射、媒体下载、卡片富渲染、外部 adapter jar 列表/删除管理、Stream registry 热切换和真实开放平台联调仍未完成；当前非文本入站先以占位文本和 metadata 进入 Agent 流程，避免消息静默丢失。
- Auth、Device Pairing、企业级 Channel/User/Agent 权限矩阵后续再做。

## Auth 与 API Token

当前 Auth 先提供本地 API Token 生命周期管理，用来为后续企业接入和外部 Channel 调用打基础。Token 数据默认保存到：

外部系统接入的完整调用示例见 [API-USAGE.md](API-USAGE.md)，包括 Token Header、同步任务、流式任务、任务查询、恢复和审批接口。

```text
.clawagent/auth/api-tokens.json
```

管理接口：

```http
GET    /api/v1/auth/tokens
POST   /api/v1/auth/tokens
DELETE /api/v1/auth/tokens/{tokenId}
```

创建请求：

```json
{
  "name": "CI Token",
  "metadata": {
    "source": "local"
  }
}
```

当前安全边界：

- token 明文只在创建响应中返回一次，列表接口不会返回明文。
- 落盘文件只保存 token 前缀、SHA-256 哈希、状态、时间和轻量 metadata。
- 撤销后的 token 会被 `ApiTokenService.verify` 判定为不可用。
- `ApiTokenService.verifyAndTouch` 会在鉴权成功时更新 `lastUsedAt`、`usageCount`、`lastUsedMethod` 和 `lastUsedPath`，用于管理台展示 token 活跃度和最后访问入口。
- 默认不启用强鉴权，避免影响本地 WebUI；企业或外部 API 接入时可显式打开。
- 后续需要补 Token 权限绑定、独立审计查询页和本地用户。

启用 API Token 鉴权：

```yaml
clawagent:
  auth:
    api-token-required: true
    protected-path-patterns:
      - /api/v1/**
    excluded-path-patterns:
      - /api/v1/health
```

调用方式：

```http
Authorization: Bearer cla_xxx
X-ClawAgent-Token: cla_xxx
```

注意：开启 `api-token-required` 后，匹配 `protected-path-patterns` 且未命中 `excluded-path-patterns` 的接口都需要 token。建议先在本地 WebUI 创建 token，再开启强鉴权。

## Device 登记

Device 用来记录本地桌面壳、浏览器扩展、外部 IM 网关等接入端。当前先提供最小登记、心跳和撤销能力，数据默认保存到：

```text
.clawagent/auth/devices.json
```

管理接口：

```http
GET    /api/v1/auth/devices
POST   /api/v1/auth/devices
POST   /api/v1/auth/devices/{deviceId}/heartbeat
DELETE /api/v1/auth/devices/{deviceId}
```

登记请求：

```json
{
  "name": "Local Desktop",
  "type": "desktop",
  "metadata": {
    "source": "admin"
  }
}
```

当前边界：

- 管理台 Device 页面可查看、登记、心跳和撤销设备。
- 后端只做本地 JSON 登记，不生成设备密钥、不做配对码校验。
- 设备级权限、用户绑定、Channel/User/Agent 策略合并和 Device Pairing 流程后续再补。

## 当前已生效配置

```yaml
server:
  port: 17891

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
  channels:
    adapter-path:
      - .clawagent/channels/adapters
    definitions: []
    configs: {}
  runtime:
    max-react-rounds: 15
    sanitization:
      enabled: true
  automation:
    enabled: true
    poll-interval-seconds: 5
    due-batch-size: 10
    max-retry-attempts: 0
    retry-backoff-seconds: 60
    pause-after-retries-exhausted: false
    default-channel-id: automation
    default-user-id: automation
  local:
    workspace-root: .clawagent/workspace
    default-shell: powershell
    permission-mode: ask
    approved-tool-ids: []
    allowed-roots:
      - .clawagent/workspace
    recent-projects: []
    test-commands: []
    project-test-commands: {}
    ignore-patterns:
      - "**/.git/**"
      - "**/.clawagent/**"
      - "**/node_modules/**"
      - "**/target/**"
      - "**/build/**"
      - "**/dist/**"
    sensitive-path-patterns:
      - "**/.env"
      - ".env"
      - "**/.env.*"
      - ".env.*"
      - "**/*.key"
      - "**/*.pem"
      - "**/*.p12"
      - "**/*.pfx"
      - "**/.ssh/**"
      - ".ssh/**"
      - "**/.git/**"
      - ".git/**"
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
          BLOCKED_PATTERNS: "**/.env,.env,**/.env.*,.env.*,**/*.key,**/*.pem,**/*.p12,**/*.pfx,**/.ssh/**,.ssh/**,**/.git/**,.git/**"
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

- `server.port`：独立服务端口，默认 `17891`。
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
- `clawagent.automation.max-retry-attempts`：自动化任务失败后的默认重试次数，默认 `0` 表示不自动重试。
- `clawagent.automation.retry-backoff-seconds`：失败重试的基础退避秒数，实际重试时间按连续失败次数递增。
- `clawagent.automation.pause-after-retries-exhausted`：重试次数耗尽后是否自动暂停该自动化任务。
- `clawagent.automation.default-channel-id`：自动化触发 Agent 请求时的默认 channel，默认 `automation`。
- `clawagent.automation.default-user-id`：自动化触发 Agent 请求时的默认 user，默认 `automation`。
- `clawagent.channels.adapter-path`：外部 Channel adapter jar 或目录列表；目录下所有 `.jar` 会通过 `ServiceLoader<ChannelRuntimeAdapter>` 加载到和内置 adapter 相同的注册表。
- `clawagent.channels.definitions`：在 `application.yml` 中声明的扁平 Channel 列表，会和内置模板及 `channels.json` 合并。
- `clawagent.channels.configs`：在 `application.yml` 中声明的 OpenClaw 风格多账号 Channel 配置，会展开为多个 `ChannelDefinition`。
- `clawagent.cost.currency`：Token 成本估算默认币种，默认 `USD`。
- `clawagent.cost.rules.<model>.input-per-million`：指定模型输入 Token 每百万单价。
- `clawagent.cost.rules.<model>.output-per-million`：指定模型输出 Token 每百万单价。
- `clawagent.cost.rules.<model>.currency`：可选，覆盖单条模型规则的币种。

单个自动化任务可以在管理台覆盖失败策略；服务端会把这些覆盖值保存在任务 metadata：

- `retry.maxAttempts`：本任务最大失败重试次数。
- `retry.backoffSeconds`：本任务基础退避秒数。
- `retry.pauseAfterExhausted`：本任务重试耗尽后是否自动暂停。
- `retry.currentAttempt`、`retry.lastError`、`retry.lastRunId`：运行时写入的连续失败状态，用于页面展示和后续调度。

`GET /api/v1/automations/{automationId}/runs` 返回的是运行历史增强视图：除基础运行状态外，还会按 `taskId` 聚合耗时、LLM 调用次数、prompt/completion/total token、工具调用总数和失败工具数。历史 task 已被清理时，接口保留基础运行记录并把摘要字段置空。

管理台“配置 -> 成本规则”会把价格规则保存到本地覆盖 YAML。Token 页不会修改原始 usage 记录，只按当前规则估算会话、任务和模型维度成本；没有匹配到模型价格时只展示 Token，不展示成本。

- `clawagent.local.workspace-root`：默认本地开发工作区，聊天页和桌面端都可以用它作为项目选择起点。
- `clawagent.local.default-shell`：默认 Shell 名称，用于页面提示和后续桌面端默认命令环境。
- `clawagent.local.permission-mode`：本地工具权限模式，支持 `ask`、`auto`、`full`、`custom`。
- `clawagent.local.approved-tool-ids`：`custom` 权限模式下默认批准的工具 ID 列表；每次任务仍会写入 metadata 以便审计。
- `clawagent.local.allowed-roots`：允许本地工具访问的根目录，会同步到 execute/filesystem 的 `ALLOWED_ROOTS`。
- `clawagent.local.test-commands` 和 `project-test-commands`：开发摘要推荐的验证命令，全局命令作为兜底，项目命令优先。
- `clawagent.local.ignore-patterns`：批量搜索、文件审查和开发摘要默认忽略的路径模式，不阻止用户显式读取单个文件。
- `clawagent.local.sensitive-path-patterns`：敏感路径模式，会同步到 filesystem 的 `BLOCKED_PATTERNS` 直接拦截；execute 命中这些 token 时会升为高危审批。

运行时会把任务 metadata 中的 `toolPermissionMode`、Channel 入站 metadata 中的 `approvalMode`、本地配置里的 `approved-tool-ids` 和早期兼容字段 `allowHighRiskTools` 统一解析为核心 `ApprovalPolicy`。这一步只负责形成可审计的审批决策输入；allowed roots、敏感路径和默认 cwd 已沉淀为 `PermissionPolicy` 领域表达，但实际路径校验仍由 execute/filesystem 的现有校验链路执行，避免重复造第二套权限判断。

### 内置能力和权限

管理台“配置 -> 内置能力”会从当前已注册 `ToolDefinition` 生成能力视图，不新增独立权限表。当前能力域包括：

- `agent`：任务创建、状态、恢复和结果汇总。
- `todo`：计划创建、步骤状态更新和 Todo 关联。
- `read`：文件读取、目录列表和文件信息。
- `search`：工作区文件名/文本搜索。
- `edit`：受控文件写入、diff 记录和 rollback。
- `execute`：前台命令执行和后台进程管理。
- `web`：Web 信息提取能力域。
- `browser`：浏览器页面交互预留能力域。
- `vscode`：IDE/桌面端编辑器联动预留能力域。

能力页展示每类能力的描述、默认参数、审计策略、启用状态、最高风险等级和匹配工具列表。权限配置复用 `clawagent.local.permission-mode` 和 `clawagent.local.approved-tool-ids`：

- `ask`：高危工具等待用户审批，低/中风险按工具策略执行。
- `auto`：明确高危工具可自动批准，但仍强制执行风险分类、allowed roots、敏感路径拦截和审计记录；不确定场景应转用户确认。
- `full`：本地控制台最高权限模式，仍保留路径限制和审计。
- `custom`：只默认批准 `approved-tool-ids` 中列出的高危工具，页面可直接勾选高危工具并保存。

Channel/user/agent 级别的细粒度权限矩阵尚未引入，后续企业治理阶段再扩展。

### Setup Wizard

管理台会复用 `clawagent.local.*`、模型配置和 `/api/v1/config/local/health` 生成本地部署向导进度。首次进入管理台时，如果工作区、模型、权限、健康检查或 MCP/Skill 检查未完成，并且当前浏览器没有跳过标记，会弹出“本地行动 Agent 初始化”弹窗。向导会直接列出健康检查中的 `warning/error` 项，便于用户定位工作区、allowed roots、默认 Shell、模型或敏感路径配置问题。跳过状态只保存在浏览器 `localStorage` 的 `clawagent.setupWizard.dismissed`，不会写入服务端配置。

`/api/v1/config/local/health` 普通检查不会访问外部模型服务，只检查本地行动 Agent 的关键前置条件：

- 默认工作区、本地覆盖配置文件和 SQLite 路径是否存在或父目录可写。
- 模型配置是否填写，深度检查时才会真实请求模型 API。
- execute/process/filesystem/todo 等关键内置工具是否已注册。
- `ALLOWED_ROOTS`、`DEFAULT_CWD`、默认 Shell、权限模式和敏感路径规则是否可用。
- `.clawagent/backups/filesystem` 与 `.clawagent/processes` 对应运行存储是否可创建或可写；这两项分别影响文件回滚备份和后台进程表。
- MCP 和 Skill 是否启用、是否已有注册项。

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
- `clawagent.toolkit.tools.execute.env`：execute 当前支持 `ALLOWED_ROOTS`、`DEFAULT_CWD`、`TIMEOUT_MS`、`PROCESS_WAIT_MS`、`MAX_OUTPUT_CHARS`、`SENSITIVE_PATH_PATTERNS`。
- `clawagent.toolkit.tools.filesystem.env`：filesystem 当前支持 `READONLY`、`ALLOWED_ROOTS`、`DEFAULT_CWD`、`BLOCKED_PATTERNS`、`IGNORED_PATTERNS`、`MAX_READ_BYTES`、`MAX_SEARCH_RESULTS`。
- `clawagent.model.mode`：`llm` 使用真实模型；`rule` 使用本地规则兜底。
- `clawagent.model.client`：模型客户端类型，`openai-compatible` 使用内置 HTTP 客户端；`spring-ai` 使用业务应用提供的 Spring AI `ChatClient.Builder`。
- `clawagent.model.planner`：`single` 使用单轮 JSON 工具规划；`react` 使用多轮 ReAct 规划；`tool-calling` 使用 OpenAI 兼容原生 tools/tool_calls。
- `clawagent.model.default`：默认模型配置 ID。
- `clawagent.model.memory-model`：记忆意图识别模型配置 ID；为空时复用默认聊天模型。
- `clawagent.models.<id>.base-url`：OpenAI 兼容 Chat Completions 根地址。
- `clawagent.models.<id>.api-key`：模型 API Key；仓库示例建议使用 `${ENV_NAME}` 占位符，本地覆盖配置可以保存真实密钥。
- `clawagent.models.<id>.model`：真实模型名。
- SiliconFlow 免费模型可以配置为 `Qwen/Qwen3-8B`；Embedding 可以配置为 `BAAI/bge-m3`。

## 本地任务审计接口

- `GET /api/v1/tasks/{taskId}/audit`：按任务聚合审计视图，返回工具调用、审批记录、文件变更、rollback、命令输出、resume 恢复点和事件时间线；审批记录会区分待审批、已批准和已拒绝。
- `GET /api/v1/audit/events`：全局 AgentEvent 审计查询，支持 `from/to/level/type/sessionId/taskId/limit`。`from/to` 支持 ISO-8601 时间，也支持管理台日期格式 `yyyy-MM-dd`；默认页面查询数量为 100，服务端最大限制为 500。
- `POST /api/v1/tasks/{taskId}/rollback-file-selection`：文件审查局部回滚接口。默认 `base=current` 时按当前文件 `startLine/endLine` 替换为备份文件同范围内容，并用 `selectedText` 校验当前内容未变化；`base=before` 时用于删除-only hunk，从备份文件取 `startLine/endLine` 行段并插入到当前文件 `insertAfterLine` 后。
- `POST /api/v1/tasks/{taskId}/resume/stream`：从历史任务继续执行。后端会继承源任务的项目目录、权限模式、知识库和附件引用，本次请求 metadata 显式传入的字段优先。
- `GET /api/v1/tasks/{taskId}/resume-state`：返回是否可继续、当前恢复 Todo、checkpoint、剩余 Todo、项目目录和恢复模式；如果当前恢复点是 failed Todo，接口会返回失败重试指令，聊天页继续执行时会把它提交给模型。
- 多次 continuation 的恢复点选择以当前未完成计划为准：优先继续 `running` Todo，其次复盘并重试 `failed` Todo，最后进入第一个 `pending` Todo；恢复点会写入 `runtime.resumeMode` 和 `runtime.resumeInstruction` 供前端和审计页展示。
- `POST /api/v1/config/local/recent-projects`：只更新 `clawagent.local.recent-projects`，用于聊天页发送任务时记住当前项目目录；不会保存配置页里尚未提交的其他草稿字段。
- 审计视图只从 `AgentEvent`、`AgentStep` 和文件变更事件派生，不新增独立审计表，保证管理台、Web Agent 和后续桌面端看到同一份执行事实。
- 管理台任务详情的“审计”页签已经接入该接口，用于快速确认一次任务实际执行过哪些高危动作、是否等待过审批、改过哪些文件以及命令退出码。

## Prompt Injection 最小防护

- Runtime 会在工具输入和工具输出中检测常见可疑指令，例如忽略前置指令、泄露系统提示词/密钥、绕过审批、隐藏操作或删除审计。
- 命中后会写入 `security.prompt_injection_detected` 事件，并在任务 metadata 中标记 `security.promptInjectionSuspected=true`。
- 被标记后的任务，后续非 `low` 工具调用会由 `ToolExecutionGuard` 强制转人工确认；已明确批准的单个工具调用仍可继续执行。
- 这不是完整企业级 Prompt Injection Defense，只是本地行动 Agent 的安全底线；敏感文件访问仍由 `sensitive-path-patterns`、filesystem `BLOCKED_PATTERNS` 和 execute 风险分类共同保护。

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
          BLOCKED_PATTERNS: "**/.env,.env,**/.env.*,.env.*,**/*.key,**/*.pem,**/*.p12,**/*.pfx,**/.ssh/**,.ssh/**,**/.git/**,.git/**"
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
