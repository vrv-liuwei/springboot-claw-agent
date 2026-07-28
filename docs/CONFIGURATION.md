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
DELETE /api/v1/channels/adapters/{filename}
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

服务端会把 `channelId + externalConversationId` 映射成稳定 `sessionId`，让同一个外部会话持续复用同一个 Agent 会话。Channel 入站会把 `approvalMode` 和 `approvedToolIds` 写入任务 metadata；普通 Web/API/Plan 任务只传 `channelId` 时，任务策略合并服务也会从 `ChannelRegistry` 反查对应 Channel 配置，继续复用现有 `ToolExecutionGuard`，不会绕过本地审批链路。

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
- `GET /api/v1/channels/adapters` 会返回当前进程加载到的 adapter 诊断信息，包括 type、实现类、来源、代码位置和是否为最终生效实现；`POST /api/v1/channels/adapters/reload` 会重新扫描 `clawagent.channels.adapter-path` 指向的外部 jar，普通入站、出站和连通性检查会立即使用新的 adapter 注册表；`POST /api/v1/channels/adapters/upload` 可上传 `.jar` 到第一个 adapter-path 目录并自动重新扫描；`DELETE /api/v1/channels/adapters/{filename}` 只允许删除 adapter 目录下的 `.jar` 并自动重新扫描。adapter 变更后，已启动且支持 `stop()` 的 Stream 会自动停止并用当前生效 adapter 重启；不支持 stop 的 SDK 会返回诊断状态，仍需要重启进程彻底断开旧连接。
- 飞书 HTTP adapter 已支持明文 URL challenge、Verification Token 校验、Encrypt Key 解密、文本消息入站、图片/文件/音视频/卡片/富文本占位文本与 metadata、入站图片/文件/音视频下载到本地 media 缓存、卡片/富文本统一 Markdown 富渲染摘要、`tenant_access_token` 缓存、`im/v1/messages` 文本回写和 `outboundMessageType=post/markdown/rich_text` 基础 post 回写。
- 钉钉 HTTP adapter 已支持自定义机器人文本入站、配置 Secret 后的入站 timestamp/sign 校验、图片/文件/Markdown/卡片占位文本与 metadata、直链图片/文件下载到本地 media 缓存、webhook HMAC-SHA256 加签、文本回写和 `outboundMessageType=markdown` markdown 回写。
- DDIO HTTP adapter 已支持文本入站、图片/视频/文件占位文本、标准事件字段和统一 `attachments` metadata；媒体缺 URL 或平台下载/解密失败时不会阻断入站，会在附件中记录 `downloadStatus/downloadReason/downloadError` 供审计和后续重试判断。
- 管理台 Channel 页已提供飞书/钉钉/DDIO 专用配置项。飞书可维护 `appId/appSecret` 或 env、`outboundMessageType=text|post`；钉钉可维护 webhook、Stream `clientId/clientSecret` 或兼容 `appKey/appSecret`、`outboundMessageType=text|markdown` 和 `markdownTitle`；DDIO 可维护 `appId/appSecret/baseUrl` 或 env，并可选择 `channel.ddio.chatScene=user|group`。保存后仍落到 Channel metadata；DDIO 出站会按“入站消息 metadata > Channel metadata > user 默认值”的顺序解析 `channel.ddio.chatScene`，管理台手动测试也能发群消息。页面同时提供“出站测试”，可手动验证飞书 receive_id、钉钉 webhook 或 DDIO `receTargetID` 是否能收到回写消息，并展示平台 HTTP 状态和响应摘要。多账号配置会在列表中展示 `channel.accountId` 和默认账号标识，便于区分 `feishu-main`、`ddio-main` 这类展开后的 Channel。
- `/api/v1/channels/{channelId}/health` 可检查通道配置。飞书会尝试获取 `tenant_access_token`；钉钉只验证 webhook 和签名配置，不主动发送测试消息，避免产生群消息噪声。
- 钉钉如果配置 `connectionMode=stream` / `dingtalk-stream`，health 会检查 `clientId/clientSecret` 或兼容的 `appKey/appSecret`，不会强制要求自定义机器人 `webhookUrl`；真实长连接仍通过 `/api/v1/channels/{channelId}/stream/start` 启动验证。
- DDIO 默认 HTTP 客户端兼容内网自签 HTTPS 地址；单元测试和外部 adapter 可通过注入 `HttpClient` 验证 token 获取和消息发送，不再绕过通用 Channel 出站门面。
- 飞书长连接 SDK 和钉钉 Stream SDK 已有进程内启动/停止/状态接口。飞书长连接由 `FeishuStreamClient` 处理 `P2MessageReceiveV1` 并转入统一 ChannelRouter，已对齐 HTTP 入站的标准事件字段，并把图片、文件、音视频、卡片/富文本统一转换为可读占位文本和 attachments metadata；钉钉 Stream 由 `DingtalkStreamClient` 接入通用事件监听和机器人消息，已抽取 `msgId/createAt/conversationType/text.content/conversationId/senderStaffId/msgtype` 等常见字段，并把图片、文件、Markdown/卡片类消息转换为 attachments，卡片和富文本会额外生成统一 Markdown 富渲染摘要。飞书 HTTP/Stream、钉钉 HTTP/Stream/Bot 都会通过 `ChannelEventMetadataSupport` 补齐统一事件 metadata。
- 飞书当前 SDK 版本的长连接 `Client` 没有公开 `stop()` 方法，停止接口会移除本地运行记录并提示需要重启进程彻底断开旧连接；钉钉 Stream 支持真实 `stop()`。
- `channels.json` 已支持两种读取格式：历史 `ChannelDefinition[]` 列表，以及 OpenClaw 风格的 `{ "channels": { "<type>": { "defaultAccount": "...", "accounts": ... } } }`。`accounts` 可以是对象或数组；读取时会展开成统一 `ChannelDefinition`，对象格式默认生成 `${type}-${accountId}`，数组格式优先使用 `accountId/id`，`default` 账号会使用 `${type}` 作为 channelId。平台级配置会先写入 metadata，账号级配置覆盖同名 key，并额外记录 `channel.configStyle=accounts`、`channel.accountId`、`channel.defaultAccount` 和 `channel.isDefaultAccount`。
- 管理接口保存、删除会保留原有落盘风格：历史 `ChannelDefinition[]` 文件继续写回列表；OpenClaw 风格对象会继续写回 `{ "channels": { "<type>": { "accounts": ... } } }` 分组结构，避免多账号配置被一次编辑打散。
- 外部 adapter jar 默认放在 `.clawagent/channels/adapters`。每个 jar 需要包含 `META-INF/services/com.github.clawagent.channel.ChannelRuntimeAdapter`，文件内容是实现类全限定名；同 `type()` 的外部 adapter 会覆盖内置实现，便于企业把飞书、钉钉或自有 IM 替换成自己的协议实现。
- 标准事件字段已统一写入 `channel.eventSource/channel.eventCategory/channel.eventSemantic/channel.eventAction/channel.eventProvider/channel.platformEventType/channel.eventType/channel.eventId/channel.eventCreateTime/channel.messageId/channel.messageCreateTime/channel.conversationId/channel.conversationType/channel.externalUserId/channel.tenantKey/channel.appId/channel.corpId` 等 metadata，便于跨通道审计和检索；`ChannelEventMetadataSupport` 会把飞书/钉钉/DDIO 的消息、反应、已读、成员变更、交互和未知事件归一成 `message.received/reaction.created/member.removed/event.received` 这类稳定语义。飞书 HTTP/长连接、钉钉 HTTP/Stream 和 DDIO 的非文本入站都会以占位文本、`attachments` metadata 和可用的本地 media 缓存进入 Agent 流程，避免消息静默丢失。附件写入时会额外生成 `channel.hasAttachments`、`channel.attachmentCount`、`channel.mediaAttachmentCount`、`channel.richAttachmentCount`、`channel.downloadedAttachmentCount`、`channel.failedAttachmentCount`、`channel.attachmentTypes`、`channel.attachmentSources`、`channel.attachmentDownloadStatuses`、`channel.attachmentFileNames`、`channel.attachmentPlatformKeys`、`channel.richRenderStatuses` 和 `channel.richRenderFormats`，便于跨任务检索、权限策略和审计列表不解析整段 JSON 也能过滤媒体消息；同时会按顺序生成 `channel.attachment.<index>.type/source/platformKey/fileName/localPath/downloadStatus/downloadReason/contentType/sizeBytes/renderStatus/renderFormat/renderText`，用于 UI 和模型精确定位某一个附件。飞书图片、文件、音频和视频已进入统一附件链路；钉钉 `downloadCode` 已支持按官方机器人文件下载接口换取 `downloadUrl` 后进入统一媒体缓存；飞书/钉钉卡片与富文本已支持 `renderStatus=rendered`、`renderFormat=markdown`、`renderText` 和 `actions` 摘要；平台原生卡片全量还原按当前产品节奏暂缓；外部 adapter jar 列表、上传、删除、重新扫描和支持 stop 的 Stream 热切换已完成；真实开放平台账号联调已按当前验收标记完成。
- 飞书/钉钉 HTTP 自动识别不再只依赖消息内容节点：飞书 `header.event_type` + `event`、钉钉 `eventType/type` + 会话/操作者/事件 ID 也会进入对应内置 adapter。反应、成员变更、已读等非消息事件会保留可读占位文本，并按 `channel.eventSemantic/channel.eventAction` 进入审计和策略链路。
- Channel media 入站缓存默认目录是 `.clawagent/channels/media`，可在 Channel metadata 配置 `mediaDownloadDir` 或 `mediaDownloadDirEnv` 覆盖；`mediaDownloadEnabled=false` 可关闭下载，只保留附件 metadata。媒体下载只接受 `http/https` URL；`mediaMaxBytes` / `mediaMaxBytesEnv` 可限制单个附件最大字节数，默认 `20971520`；`mediaDownloadTimeoutMs` / `mediaDownloadTimeoutMsEnv` 可覆盖下载超时，默认沿用 adapter 传入值。下载失败或超过限制不会阻断入站，会在 attachments 中记录 `downloadStatus/downloadReason/downloadError`。
- 钉钉 `downloadCode` 下载需要在 Channel metadata 中配置 `accessToken` / `accessTokenEnv` 和 `robotCode` / `robotCodeEnv`；`robotCode` 未显式配置时会兼容读取 `clientId/appKey/appId`。如需代理或本地 mock，可用 `downloadCodeApiUrl` / `downloadCodeApiUrlEnv` 覆盖默认官方 API。凭证缺失时不会阻断入站，附件会记录 `downloadStatus=skipped` 和对应 `downloadReason`。
- Auth 已提供 API Token、本地用户管理、Channel 外部用户绑定和 Device Pairing 基础能力；任务入口已合并 Channel/User/Device/Agent 的轻量审批策略。Channel 层既支持入站 metadata，也支持按 `channelId` 从注册表反查配置；User 层支持本地用户 metadata 和 `clawagent.auth.role-policies` 角色模板；Agent 层支持在任务 metadata 中传入 `agent.role`、`agent.permissionMode`、`agent.approvedToolIds` 和 `agent.isolation=read-only`，其中 `agent.role` 会匹配 `clawagent.agents.policies` 中的角色模板，用于限制子 Agent 或特定 Agent 任务的工具边界。企业级组织/用户组矩阵后续再做。

## Auth、本地用户与 API Token

当前 Auth 先提供本地 API Token 生命周期管理、单机本地用户管理、首次 owner 初始化和本地登录会话，用来为后续企业接入、外部 Channel 调用和桌面端身份接入打基础。身份与权限数据默认保存到 `clawagent.persistence.sqlite.path` 指向的 SQLite 数据库：

外部系统接入的完整调用示例见 [API-USAGE.md](API-USAGE.md)，包括 Token Header、同步任务、流式任务、任务查询、恢复和审批接口。

```text
默认数据库：.clawagent/data/clawagent.db
Auth 表：
- auth_api_token
- auth_local_user
- auth_local_user_session
- auth_device
- auth_channel_user_binding
```

说明：`clawagent.persistence.sqlite.path` 已覆盖任务、会话、消息、事件、Todo、Plan、自动化运行数据，以及 Auth 本地用户、登录会话、API Token、Device 和 Channel 外部用户绑定。业务层只依赖 `LocalUserStore`、`LocalUserSessionStore`、`ApiTokenStore`、`DeviceStore` 和 `ChannelUserBindingStore`，默认由 `claw-agent-persistence-sqlite` 提供 SQLite 实现；当前已用跨 Store 实例 round-trip 测试验证身份数据能从同一个 SQLite 文件恢复。后续迁移到企业身份源时继续替换 Store 实现，不改 Controller、Resolver、鉴权拦截器或管理台接口。

管理接口：

```http
GET    /api/v1/auth/tokens
POST   /api/v1/auth/tokens
DELETE /api/v1/auth/tokens/{tokenId}

GET    /api/v1/auth/setup
POST   /api/v1/auth/setup

GET    /api/v1/auth/users
POST   /api/v1/auth/users
POST   /api/v1/auth/users/{userId}/password
POST   /api/v1/auth/users/{userId}/permissions
DELETE /api/v1/auth/users/{userId}

POST   /api/v1/auth/login
GET    /api/v1/auth/me
POST   /api/v1/auth/logout
GET    /api/v1/auth/sessions
DELETE /api/v1/auth/sessions/{sessionId}

GET    /api/v1/channels/{channelId}/users
POST   /api/v1/channels/{channelId}/users
DELETE /api/v1/channels/{channelId}/users?externalUserId=ou_xxx
```

创建请求：

```json
{
  "name": "CI Token",
  "ownerUserId": "user-1",
  "ownerUsername": "alice",
  "permissionMode": "custom",
  "approvedToolIds": [
    "builtin.execute.command",
    "builtin.filesystem.read_text_file"
  ],
  "scopes": [
    "tasks:read",
    "tasks:write"
  ],
  "expiresAt": null,
  "metadata": {
    "source": "local"
  }
}
```

当前安全边界：

- token 明文只在创建响应中返回一次，列表接口不会返回明文。
- SQLite 只保存 token 前缀、SHA-256 哈希、状态、归属用户、权限范围、时间和轻量 metadata。
- 撤销或过期后的 token 会被 `ApiTokenService.verify` 判定为不可用。
- `ApiTokenService.verifyAndTouch` 会在鉴权成功时更新 `lastUsedAt`、`usageCount`、`lastUsedMethod` 和 `lastUsedPath`，用于管理台展示 token 活跃度和最后访问入口。
- `clawagent.auth.required=true` 会开启 `/api/v1/**` 本地鉴权拦截；请求可携带 `Authorization: Bearer cla_xxx` / `X-ClawAgent-Token` 作为 API Token，携带 `Authorization: Bearer clas_xxx` / `X-ClawAgent-Session` 作为本地用户 session，或携带 `X-ClawAgent-Device-Id` + `X-ClawAgent-Device-Secret` 作为已配对设备凭证。
- API Token 可维护 `ownerUserId/ownerUsername/permissionMode/approvedToolIds/scopes/expiresAt`；鉴权通过后会先按非空 `scopes` 校验接口访问范围，再把 Token 身份和权限范围写入 task metadata，并与 User/Device/Channel/Agent 策略合并。
- `scopes` 为空时兼容历史服务级 Token；非空时按接口域和 HTTP 方法校验，支持 `tasks:read`、`tasks:write`、`tasks:*`、`admin:*` 和 `*`。接口域由 `clawagent.auth.scope-mappings` 配置，默认包括 `tasks/plans/sessions/auth/channels/config/knowledge/memory/mcp/skills/attachments`；未知接口按 `api:read/write` 兜底。
- `clawagent.auth.api-token-required=true` 是兼容旧配置项，仍会开启同一条鉴权链路；新配置建议使用 `required`。
- 默认排除 `/api/v1/health`、`/api/v1/auth/login` 和 `/api/v1/auth/setup`，避免健康检查、登录接口和首次 owner 初始化被自身拦截。
- `/api/v1/auth/setup` 只允许在本地用户为空时创建首个 `owner`；一旦已有用户，重复 setup 会返回冲突错误。当前内置角色为 `owner/admin/operator/viewer/user`。`viewer` 会默认收紧到 `read-only`；其它角色默认不自动提权，但可通过 `clawagent.auth.role-policies` 配置角色级 `permissionMode/approvalMode/approvedToolIds` 模板，真正的工具执行边界仍由审批策略和权限策略强制控制。
- 本地用户密码使用 PBKDF2-HMAC-SHA256、随机 salt 和迭代哈希保存，列表接口不返回 hash、salt 或明文密码。
- 删除本地用户当前是逻辑禁用；禁用用户不能通过 `LocalUserService.verify` 校验。
- 本地用户可维护 `permissionMode/approvedToolIds`，也可由 `clawagent.auth.role-policies` 提供角色默认模板；任务入口会把用户策略与 Channel/Device/Agent 策略合并后交给 `ToolExecutionGuard` 强制执行。用户自身 metadata 优先于角色模板；Channel 策略优先读取任务 metadata 中的显式入站策略，普通任务没有显式策略时按 `channelId` 读取 `ChannelRegistry`；Agent 策略读取 `agent.permissionMode/agent.approvedToolIds`，子 Agent 派发会把请求侧通用 `toolPermissionMode/approvedToolIds` 转存到这两个 agent 字段用于审计和后续扩展，只读隔离仍读取 `agent.isolation=read-only` 并覆盖本次有效执行策略。当最终策略为 `read-only` 时，会清理合并过程中产生的工具白名单，避免解释视图或审计误判为“只读但仍放行高危工具”。
- 外部 IM 用户可通过 `/api/v1/channels/{channelId}/users` 绑定到本地用户；管理台 Channel 页已提供外部用户 ID、外部用户名、本地用户选择、绑定列表和解绑入口。飞书、钉钉、DDIO 等 Channel 入站任务会先按 `channelId + externalUserId` 解析绑定关系，再把 `localUserId/user.id/user.username` 写入任务 metadata，并复用本地用户、Channel、Device、Agent 的策略合并结果。
- admin 与 app 登录成功后会把本地 session token、userId 和 username 保存到浏览器 `localStorage`；admin 与 app 前端的普通任务、恢复任务、创建会话和 Plan 执行都会自动携带 `Authorization: Bearer clas_xxx`，并把当前用户 ID 写入任务 `userId/localUserId`，避免 WebUI/App 任务继续以匿名 `console/local` 身份运行；`claw-agent-app` 顶部提供紧凑的本地用户登录/退出入口。
- 管理台创建 API Token 时会优先绑定当前登录本地用户，并继承该用户的 `permissionMode/approvedToolIds` 作为 Token 默认权限范围；未登录时仍可创建兼容历史的无主 Token。
- 登录成功会返回一次性可见的 `sessionToken`；会话表只保存 token 前缀、SHA-256 哈希、用户摘要、创建/过期/撤销/最后使用时间。
- `/api/v1/auth/me` 和 `/api/v1/auth/logout` 支持 `Authorization: Bearer clas_xxx` 或 `X-ClawAgent-Session: clas_xxx`；管理员可通过 `/api/v1/auth/sessions` 查看本地登录会话，并用 sessionId 撤销指定会话，审计只记录会话摘要和 token 前缀。
- 默认不启用强鉴权，避免影响本地 WebUI；企业或外部 API 接入时可显式打开。
- `clawagent.rate-limit.enabled=true` 可开启 HTTP API 入口限流。限流在鉴权之后执行，优先按 API Token、本地用户或设备分桶，没有认证身份时按客户端 IP 分桶；超过限制会返回 `429 rate_limited`、`Retry-After` 和 `X-ClawAgent-RateLimit-*` 响应头。当前实现是单机固定窗口限流，适合本地部署、单节点 Channel 回调和外部 API Token 接入；分布式限流后续随 Redis/多节点阶段补。
- 后续需要补独立审计查询页和企业身份源接入。

启用 API Token 鉴权：

```yaml
clawagent:
  auth:
    api-token-required: true
    protected-path-patterns:
      - /api/v1/**
    excluded-path-patterns:
      - /api/v1/health
    scope-mappings:
      tasks:
        - /api/v1/tasks/**
        - /api/v1/steps/**
        - /api/v1/agents/**
        - /api/v1/todos/**
      config:
        - /api/v1/config/**
    role-policies:
      operator:
        permission-mode: custom
        approved-tool-ids:
          - builtin.execute.command
          - builtin.filesystem.read_text_file
      viewer:
        permission-mode: read-only
  rate-limit:
    enabled: true
    default-limit: 120
    default-window-seconds: 60
    protected-path-patterns:
      - /api/v1/**
    excluded-path-patterns:
      - /api/v1/health
    rules:
      - name: task-write
        path-patterns:
          - /api/v1/tasks/**
          - /api/v1/agents/**
          - /api/v1/plans/**
        methods:
          - POST
          - PUT
          - PATCH
          - DELETE
        limit: 30
        window-seconds: 60
```

调用方式：

```http
Authorization: Bearer cla_xxx
X-ClawAgent-Token: cla_xxx
```

注意：开启 `required` 后，匹配 `protected-path-patterns` 且未命中 `excluded-path-patterns` 的接口都需要 API Token 或本地用户 session。建议先通过 Auth 页或 `/api/v1/auth/setup` 初始化 owner，再开启强鉴权。`api-token-required` 是兼容旧项。

## Device 登记与配对

Device 用来记录本地桌面壳、浏览器扩展、外部 IM 网关等接入端。当前提供直接登记、配对码绑定、设备密钥校验、密钥轮换、用户绑定、心跳、撤销和设备级权限字段绑定，数据默认保存到同一个 SQLite 数据库的 `auth_device` 表：

```text
默认数据库：.clawagent/data/clawagent.db
```

管理接口：

```http
GET    /api/v1/auth/devices
POST   /api/v1/auth/devices
POST   /api/v1/auth/devices/pairing-codes
POST   /api/v1/auth/devices/pair
POST   /api/v1/auth/devices/{deviceId}/heartbeat
POST   /api/v1/auth/devices/{deviceId}/verify
POST   /api/v1/auth/devices/{deviceId}/secret/rotate
POST   /api/v1/auth/devices/{deviceId}/user
POST   /api/v1/auth/devices/{deviceId}/permissions
DELETE /api/v1/auth/devices/{deviceId}
```

登记请求：

```json
{
  "name": "Local Desktop",
  "type": "desktop",
  "permissionMode": "ask",
  "approvedToolIds": [],
  "metadata": {
    "source": "admin"
  }
}
```

当前边界：

- 管理台 Device 页面可查看、登记、生成配对码、维护设备权限、心跳和撤销设备。
- `claw-agent-app` 设置页可输入管理台生成的配对码完成设备配对；配对成功后本地保存 `deviceId/deviceSecret/name/type/secretPrefix`，并提供密钥校验、心跳和解除本地配对入口。
- 配对码只在创建响应中返回一次，落盘只保存哈希；默认有效期 600 秒，可在 60 到 3600 秒之间调整。
- 设备完成配对后返回一次性 `deviceSecret`，服务端只保存哈希和前缀；客户端需要自行保存密钥。
- 设备密钥轮换会立即替换服务端保存的密钥哈希，旧密钥随即失效；新密钥只在轮换响应中返回一次。
- 设备可绑定本地用户 ID 和展示用户名；任务 metadata 只携带 `deviceId` 时，策略合并会自动叠加该绑定用户的权限策略。
- `claw-agent-app` 创建会话、普通任务和 Plan 执行会携带 `deviceId`、`device.id`、`client.deviceId`、`device.name` 和 `device.type`；开启强鉴权后也可用 `X-ClawAgent-Device-Id` + `X-ClawAgent-Device-Secret` 作为设备凭证访问任务、会话、计划和附件等非管理接口。设备密钥只参与鉴权校验，不会进入任务 metadata。
- `permissionMode` 和 `approvedToolIds` 已作为设备级权限绑定字段保存；Web/API/计划执行入口会按 `local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement` 合并到任务 metadata，再由 `ToolExecutionGuard` 强制执行。Agent 层如果提供 `agent.role`、`agent.permissionMode/agent.approvedToolIds`，也会作为 `scope=agent` 的策略层参与合并；子 Agent 派发保留请求策略意图，但 `agent.isolation=read-only` 仍作为最终只读强约束。
- 企业级权限矩阵、设备级审计查询和系统钥匙串级密钥保存后续再补。

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

策略解析预览接口：

```http
POST /api/v1/config/policy/resolve
```

请求示例：

```json
{
  "channelId": "ddio",
  "userId": "alice",
  "metadata": {
    "deviceId": "device-1",
    "apiToken.id": "token-1",
    "apiToken.permissionMode": "custom",
    "apiToken.approvedToolIds": "builtin.execute.command,builtin.filesystem.read_text_file"
  }
}
```

该接口只解析，不保存配置。返回值会包含最终 `effectiveMode/source/scope/reason/approvedToolIds`、每一层命中的 user/api-token/device/task/agent 策略，以及 Runtime 实际会收到的 `effectiveMetadata`。Agent 层可通过 `agent.role`、`agent.permissionMode`、`agent.approvedToolIds` 或 `agent.isolation=read-only` 参与预览。它用于排查“为什么这个任务最终需要审批或被只读隔离”，不是新的权限引擎。

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

当前已接入轻量级 Channel/User/API Token/Device/Agent 审批策略合并，并提供 `/api/v1/config/policy/resolve` 预览一次任务的实际策略命中层级；Channel 层支持入站 metadata 和 `channelId` 注册表反查两种来源，User 层支持本地用户 metadata 和 `clawagent.auth.role-policies` 角色模板，Agent 层支持任务 metadata 中的 `agent.role/agent.permissionMode/agent.approvedToolIds/agent.isolation`，`agent.role` 会读取 `clawagent.agents.policies` 角色模板。组织、用户组、通道账号之间的细粒度企业权限矩阵尚未引入，后续企业治理阶段再扩展。

### 子 Agent 拆分策略 metadata

子 Agent 仍复用现有任务和 Todo/Runtime 链路，不单独引入第二套执行引擎。`/api/v1/agents/{parentTaskId}/subtasks`、`/subtasks/batch` 和 `/subtasks/from-plan` 会在子任务 metadata 中写入以下字段，供管理台、审计和后续 worker/非只读 Agent 扩展使用：

- `agent.split.source`：拆分来源，当前为 `manual` 或 `plan`。
- `agent.split.strategy`：调用方传入的策略名，例如 `fanout`、`plan-items`、`review`。
- `agent.split.profile`：后端规范化后的策略 profile；未知策略会落到 `custom`，避免前端把任意字符串当作内置策略。
- `agent.split.rolePolicy`：角色来源，`request-role` 表示调用方显式传入角色，`default-role` 表示使用默认子 Agent 角色。
- `agent.dispatch.parallel` / `agent.dispatch.maxParallelism`：本次派发是否并行以及实际并发上限。
- `agent.split.highRiskPolicy`：仅 Plan 派发使用，记录是否跳过 high 风险或需要审批的计划项。
- `agent.split.dispatchMode`：仅 Plan 派发使用，默认 `manual` 保持历史行为；传入 `auto` 时，服务端只派发低风险、无需审批、明确偏只读审查/分析的计划项。
- `agent.split.autoCandidate`：仅 `dispatchMode=auto` 且计划项被选中时写入 `true`，便于审计确认该子任务来自自动候选筛选。
- `agent.isolation.requested` / `agent.isolation.effective`：调用方请求的隔离级别和服务端最终生效的隔离级别；当前非 `read-only` 请求会被降级。
- `agent.isolation.profile` / `agent.isolation.enforcement`：当前隔离实现画像和强制执行点；现阶段为 `metadata-read-only` + `tool-guard`。
- `agent.worker.requested` / `agent.worker.effective`：调用方通过请求字段 `workerMode` 或 metadata 请求的 worker 模式，以及服务端最终生效的 worker 模式。
- `agent.worker.configured`：服务端是否已经配置子 Agent Runtime worker 启动命令；默认 `false`。
- `agent.worker.eligible` / `agent.worker.mode`：子 Agent 请求是否具备进入独立 worker 调度的配置条件，以及配置的 worker 模式。未配置时为 `false` + `not-started`；配置 `clawagent.agents.worker.enabled=true` 且提供 `command` 后会变为 `true` + `external-process`。
- `agent.worker.maxConcurrent` / `agent.worker.acquireTimeoutMs`：仅在 worker 配置可用时写入，用于后续进程 dispatcher 限流。
- `agent.worker.reason`：仅在调用方请求 `process`、`worker` 等独立 worker 模式但当前被降级时写入，说明为什么没有进入独立进程 Runtime。
- `agent.worker.pid` / `agent.worker.exitCode` / `agent.worker.elapsedMs`：进入 external-process worker 后由主服务写回，证明实际启动的进程、退出码和运行耗时。
- `agent.worker.timeoutMs` / `agent.worker.maxOutputBytes`：本次 worker 调度采用的超时和输出捕获上限。
- `agent.worker.stdoutBytes` / `agent.worker.stdoutCapturedBytes` / `agent.worker.stdoutTruncated`：stdout 实际读取字节数、保留字节数和是否截断；stderr 也会生成同名 `stderr*` 字段。
- `agent.worker.timedOut` / `agent.worker.terminated` / `agent.worker.interrupted`：记录 worker 是否超时、是否被主服务终止、等待过程是否被中断。失败时这些字段也会随 `SubAgentWorkerDispatchException` 写回子任务 metadata。

这些字段只描述拆分、调度和隔离审计状态，不会放宽工具权限。未进入 external-process worker 时，子 Agent 仍强制 `agent.isolation=read-only`，有效执行策略仍由 `ToolExecutionGuard` 和底层工具权限校验决定。

用户角色权限模板位于 `clawagent.auth.role-policies`。本地用户没有显式 `permissionMode/approvedToolIds` 时，会按用户角色读取模板；本地用户 metadata 始终优先于角色模板。

Agent 角色权限模板位于 `clawagent.agents.policies`。调度层只需要在任务 metadata 写入 `agent.role`，
服务端会按角色模板生成 `scope=agent` 的策略层，再和 Channel/User/API Token/Device/Task 等策略取更严格模式与工具白名单交集。
默认模板保持 `enabled=false`，不会改变现有执行权限：

```yaml
clawagent:
  agents:
    policies:
      coder:
        enabled: false
        permission-mode: custom
        approved-tool-ids:
          - builtin.filesystem.read_text_file
          - builtin.filesystem.write_text_file
          - builtin.execute.command
      reviewer:
        enabled: false
        permission-mode: read-only
        approved-tool-ids: []
```

子 Agent Runtime worker 的配置位于 `clawagent.agents.worker`：

```yaml
clawagent:
  agents:
    worker:
      enabled: false
      mode: external-process
      command: ""
      args: []
      max-concurrent: 2
      acquire-timeout-ms: 5000
      timeout-ms: 300000
      max-output-bytes: 1048576
```

注意：`toolkit.tools.execute.env.WORKER_*` 是高危命令执行的隔离 worker，负责运行一条本机命令；`clawagent.agents.worker.*` 是子 Agent Runtime 进程隔离配置，负责运行一个子任务。两者可以复用同一个 `claw-agent-worker` jar，但入口类和协议不同：命令隔离默认走 jar 的 `Main-Class=com.github.clawagent.worker.ClawAgentWorkerMain`；子 Agent 适配入口需要显式配置 `com.github.clawagent.worker.ClawAgentSubAgentWorkerMain`。

external-process worker 协议：

- Server 通过 stdin 写入 JSON：`protocol=CLAW_SUBAGENT_WORKER_V1`、`taskId`、`input`、`sessionId`、`channelId`、`userId`、`metadata`。
- Worker 正常结束时在 stdout 输出 `CLAW_SUBAGENT_WORKER_RESULT_V1`，随后输出结果 JSON：`answer`、`status`、`metadata`。
- Server 会按 `max-concurrent` 限流，按 `acquire-timeout-ms` 等待槽位，按 `timeout-ms` 超时强杀进程树，并按 `max-output-bytes` 截断 stdout/stderr 缓存。

`claw-agent-worker` 内置 `com.github.clawagent.worker.ClawAgentSubAgentWorkerMain` 作为子 Agent worker 适配入口。它不内置模型 Runtime，而是把 server 下发的任务 JSON 转发给 `--` 后面的下游 Runtime 命令；下游命令可以直接输出标准 marker，也可以输出普通文本，由适配入口包装为标准结果。当前验收已覆盖普通输出包装、标准 marker 透传、下游 Runtime 失败、适配入口超时终止和缺少下游 Runtime 命令的失败返回；如果后续要做“worker 内置完整模型 Runtime”，应作为独立执行引擎工程推进，不混进现有轻量适配入口。

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

### Setup Wizard

管理台会复用 `clawagent.local.*`、模型配置和 `/api/v1/config/local/health` 生成本地部署向导进度。首次进入管理台时，如果工作区、模型、权限、健康检查或 MCP/Skill 检查未完成，并且当前浏览器没有跳过标记，会弹出“本地行动 Agent 初始化”弹窗。向导会直接列出健康检查中的 `warning/error` 项，便于用户定位工作区、allowed roots、默认 Shell、模型、worker jar 或敏感路径配置问题。跳过状态只保存在浏览器 `localStorage` 的 `clawagent.setupWizard.dismissed`，不会写入服务端配置。`claw-agent-app` 设置页的“常规 / 本地健康检查”也会调用同一个接口，普通用户不用打开管理台即可看到 worker jar 是否存在、实际解析路径和执行目录是否越界。

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
- `clawagent.toolkit.tools.execute.env`：execute 当前支持 `ALLOWED_ROOTS`、`DEFAULT_CWD`、`TIMEOUT_MS`、`MAX_TIMEOUT_MS`、`PROCESS_WAIT_MS`、`MAX_OUTPUT_CHARS`、`SENSITIVE_PATH_PATTERNS`、`WORKER_ENABLED`、`WORKER_JAR`、`WORKER_JAVA`、`WORKER_JVM_MAX_HEAP`、`WORKER_MAX_OUTPUT_BYTES`、`WORKER_MAX_CPU_TIME_MS`、`WORKER_MAX_MEMORY_BYTES`、`WORKER_MAX_CONCURRENT`、`WORKER_ACQUIRE_TIMEOUT_MS`、`WORKER_TERMINATION_GRACE_MS`、`WORKER_SANDBOX_ENABLED`、`WORKER_SANDBOX_ROOT`、`WORKER_KEEP_SANDBOX`、`WORKER_ALLOWED_ENV_NAMES`、`WORKER_BLOCKED_ENV_NAME_FRAGMENTS`。
- `TIMEOUT_MS`：命令默认超时时间，默认 `30000`。调用方可传 `timeoutMs` 覆盖默认值。
- `MAX_TIMEOUT_MS`：单次命令允许的最大超时时间，默认 `120000`。调用方传入更大的 `timeoutMs` 时会被压到该上限，工具结果会记录 `requestedTimeoutMs`、实际 `timeoutMs` 和 `timeoutCapped=true`，避免模型传入极大超时后长时间占住 worker 或主服务执行线程。
- `WORKER_ENABLED`：是否让 high risk 命令、`builtin.process.start` 和 Spring 环境下的 Script Skill 通过 `claw-agent-worker` 独立 JVM 子进程隔离执行，默认 `true`。low/medium 风险 execute 命令仍走主服务现有 execute 链路，避免把普通查询命令全部绕到 worker。
- `WORKER_JAR`：命令隔离 worker jar 路径，默认 `claw-agent-worker/target/claw-agent-worker-1.0.0-SNAPSHOT.jar`。本地开发时先执行 `mvn -pl claw-agent-worker -DskipTests package` 生成 jar；如果部署目录不同，建议改成绝对路径。相对路径会先按当前 JVM `user.dir` 解析，找不到时沿父目录向上查找，兼容从项目根、`claw-agent-server` 模块目录或 IDE 工作目录启动。执行失败时错误信息会打印 `WORKER_JAR`、`user.dir` 和已检查路径。
- `WORKER_JAVA`：worker 使用的 Java 可执行文件；为空时使用当前服务 JVM 的 `java.home/bin/java`。只有需要指定独立 JRE/JDK 时才配置。
- `WORKER_JVM_MAX_HEAP`：worker JVM 最大堆，例如 `256m`，启动 worker 时会转成 `-Xmx256m`；默认 `256m`。
- `WORKER_MAX_OUTPUT_BYTES`：worker 内单个 stdout/stderr 最大保留字节数，默认 `1048576`。超过后 worker 会继续 drain 管道但丢弃后续输出，避免高危命令大输出导致进程阻塞或主服务内存膨胀。
- `WORKER_MAX_CPU_TIME_MS`：worker 内命令进程树累计 CPU 时间上限，默认 `0` 表示关闭。配置为正数后，worker 会轮询 root + descendants 的 `ProcessHandle.Info.totalCpuDuration`，超限时终止整棵进程树，并在工具结果里记录 `workerResourceLimited=true`、`workerResourceLimitReason=cpu-time` 和 `workerCpuTimeMs`。该能力依赖当前 JDK/OS 是否暴露进程 CPU 时间。
- `WORKER_MAX_MEMORY_BYTES`：worker 内命令进程树驻留内存/工作集上限，默认 `0` 表示关闭。配置为正数后，worker 会按平台限制 root + descendants 的内存占用，超限时终止整棵进程树，并在工具结果里记录 `workerResourceLimited=true`、`workerResourceLimitReason=memory` 和 `workerMemoryBytes`。Windows 使用 Job Object 硬限制，不再用 `powershell/tasklist/wmic` 轮询；Linux 使用 `/proc/<pid>/status` 的 `VmRSS` 软采样，其它 Unix 尝试 `ps rss` 软采样。
- `WORKER_MAX_CONCURRENT`：主服务侧允许同时运行的隔离 worker 数量，默认 `2`。超过并发上限的高危命令会先排队等待。
- `WORKER_ACQUIRE_TIMEOUT_MS`：高危命令等待 worker 执行槽位的最长时间，默认 `5000`。超时仍拿不到槽位时直接返回错误，避免任务线程无界堆积。
- `WORKER_TERMINATION_GRACE_MS`：worker 超时后先尝试正常终止命令进程树的等待窗口，默认 `1500`；窗口结束仍存活时会执行强制终止。工具结果会记录该值，便于审计当前强杀策略。
- `WORKER_SANDBOX_ENABLED`：是否为每次 worker 执行创建独立临时运行目录，默认 `true`。该目录会注入到真实命令环境变量 `CLAW_WORKER_SANDBOX_DIR/TMP/TEMP/TMPDIR`，用于隔离工具临时文件；它不是容器级文件系统隔离，`cwd` 和可访问路径仍由 allowed roots、敏感路径策略和具体工具权限约束。
- `WORKER_SANDBOX_ROOT`：worker 临时运行目录的根路径，默认 `.clawagent/worker-sandbox`。相对路径会按本次 `cwd` 解析，并且必须落在 execute allowed roots 内。
- `WORKER_KEEP_SANDBOX`：前台 worker 命令结束后是否保留本次临时运行目录，默认 `false`。`builtin.process.start` 后台进程会强制保留 sandbox，因为服务进程仍可能继续使用该目录；工具结果会记录 `workerSandboxPath/workerSandboxKept` 便于排查。
- `WORKER_ALLOWED_ENV_NAMES`：高危命令进入 worker 时允许继承的环境变量名白名单，默认只包含 `PATH,PATHEXT,JAVA_HOME,SystemRoot,ComSpec,TEMP,TMP,USERPROFILE,HOME,APPDATA,LOCALAPPDATA,ProgramData,M2_HOME,MAVEN_OPTS,GRADLE_USER_HOME` 等基础运行变量。主服务启动 worker 前会先清空环境，再按白名单重建，避免高危命令默认拿到主服务全部密钥；如果某个高危命令确实需要 `ANYSEARCH_API_KEY`、`NPM_TOKEN` 这类变量，必须把完整变量名显式加入该配置。
- `WORKER_BLOCKED_ENV_NAME_FRAGMENTS`：worker 仍会按变量名片段过滤敏感变量，默认 `TOKEN,SECRET,PASSWORD,PASSWD,API_KEY,ACCESS_KEY,PRIVATE_KEY,CREDENTIAL,AUTHORIZATION,COOKIE`。显式写入 `WORKER_ALLOWED_ENV_NAMES` 的完整变量名优先于片段过滤，用于受控放行必要凭证；worker 启动真实命令前会再次执行同样过滤，工具结果记录 `workerEnvBlockedCount`。
- worker 会继承 execute 的 `ALLOWED_ROOTS` 作为 `--allowedRoot` 参数并在隔离进程内再次校验 `cwd`。`ALLOWED_ROOTS` 限制的是被执行命令的工作目录和后台日志目录，不要求 `WORKER_JAR` 本身必须位于 allowed roots 内；`WORKER_JAR` 是主服务启动隔离 JVM 的程序文件路径。`WORKER_JAR` 使用相对路径时，主服务会先按当前启动目录解析，找不到时逐级向父目录查找项目根下的 worker jar，避免任务 `cwd` 在 `.clawagent/workspace` 时误判 jar 不存在。主进程仍负责正常工作目录解析；worker 内部校验用于防止后续入口复用或参数构造错误导致隔离进程越界执行。
- `/api/v1/config/local/health` 会复用同一套 worker jar 查找规则输出 `worker-jar` 健康项。排查隔离 worker 时先看该接口或管理台/App 的本地健康检查：`ok` 会显示 `resolved=` 实际 jar；`error` 会显示 `WORKER_JAR`、`user.dir` 和 `checked=` 已检查路径。只有命令 `cwd` 和日志目录需要落在 `ALLOWED_ROOTS` 内，`WORKER_JAR` 可以配置为项目构建产物的绝对路径。
- `builtin.process.start` 复用 execute 的 allowed roots：`cwd` 和显式/默认 `logPath` 都必须落在 allowed roots 内，避免后台进程把运行日志写到授权目录外。`WORKER_ENABLED=true` 时，后台进程由 worker 隔离启动，worker 返回 pid 后主服务只持久化托管记录；后续 `status/logs/stop` 通过 `ProcessHandle` 和日志文件继续管理。后台进程的 worker sandbox 会保留，并在工具结果中返回路径。
- Script Skill 复用 execute worker 配置，不单独维护 `SKILL_WORKER_*`。`metadata.executor.env` 会作为显式 env 传入 worker；变量名仍会按 `WORKER_BLOCKED_ENV_NAME_FRAGMENTS` 拦截，除非完整变量名被加入 `WORKER_ALLOWED_ENV_NAMES`。
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
- `GET /api/v1/audit/events`：全局 AgentEvent 审计查询，支持 `from/to/level/type/sessionId/taskId/userId/channelId/toolId/riskLevel/detailKey/detailValue/q/limit`。`from/to` 支持 ISO-8601 时间，也支持管理台日期格式 `yyyy-MM-dd`；`userId/channelId/toolId/riskLevel/detailKey/detailValue/q` 会在事件详情中做只读过滤，便于按 API Token、Device、Channel、Agent 或工具风险排查策略命中原因；默认页面查询数量为 100，服务端最大限制为 500。
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

当前审批链已扩展到本地配置、Channel、用户、API Token、Device、任务 metadata 和只读子 Agent 隔离层。Channel 入站会显式写入策略 metadata；普通任务只带 `channelId` 时会反查 Channel 注册表配置。更完整的企业组织/角色矩阵和策略审计视图后续继续增强。
