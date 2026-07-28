# ClawAgent Configuration

本文档记录本地 ClawAgent 的主要配置方式，重点覆盖模型、图片理解、附件入口和 DDIO 通道。

## 1. 配置文件入口

ClawAgent 支持两类通道配置来源：

- `claw-agent-server/src/main/resources/application.yml` 中的 `clawagent.channels.configs`。
- 运行目录下的 `.clawagent/channels/channels.json`。

两者使用同一种账号式结构。`application.yml` 适合本地开发默认配置，`channels.json` 适合运行时或外部化账号配置。

密钥不要写入仓库，优先使用 `appSecretEnv`、`api-key: ${ENV_NAME:}` 这类环境变量引用。

## 2. LLM 和 Vision 模型

模型配置保持当前格式，只给模型增加 `vision` 能力字段。

```yaml
clawagent:
  model:
    mode: llm
    planner: react
    default: deepseek-v4-flash
    memory-model: siliconflow-qwen3-8b
    vision-model: qwen3-vl
  models:
    deepseek-v4-flash:
      provider: deepseek
      base-url: https://api.deepseek.com
      model: deepseek-v4-flash
      api-key: ${DEEPSEEK_API_KEY:}
      temperature: 0.2
      timeout-seconds: 60
      vision: false

    siliconflow-qwen3-8b:
      provider: siliconflow
      base-url: https://api.siliconflow.cn/v1
      model: Qwen/Qwen3-8B
      api-key: ${SILICONFLOW_API_KEY:}
      temperature: 0.2
      timeout-seconds: 60
      vision: false

    qwen3-vl:
      provider: dashscope
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model: qwen3-vl-flash
      api-key: ${DASHSCOPE_API_KEY:}
      temperature: 0.2
      timeout-seconds: 60
      vision: true
```

图片处理规则：

1. `model.default` 对应模型 `vision: true`：图片不做预识别，最终回复阶段直接把图片按 OpenAI-compatible 多模态格式发送给默认模型。
2. `model.default` 对应模型 `vision: false`，并配置了 `model.vision-model`：先用 `vision-model` 生成图片描述，再把描述写入 `metadata.attachments.modelContext`，最后交给默认模型回答。
3. `model.default` 不支持 vision，且没有配置 `model.vision-model`：只保留附件元数据，并向模型上下文写入“不支持图片理解”的明确提示。

当前实现统一使用 base64 data URL 传图，适配 OpenAI-compatible 的图片参数格式：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

ReAct、Planner、Tool Calling 阶段仍然只处理文本。原生图片只在最终回复生成阶段进入支持 vision 的模型。

## 3. AgentRequest 附件约定

`AgentRequest.input` 永远只表示用户输入的文字问题。图片、文件和其他附件统一放在 `metadata.attachments`，值是 JSON 字符串。

示例：

```json
[
  {
    "type": "image",
    "fileName": "photo.png",
    "localPath": "C:/Users/Administrator/.clawagent/media/inbound/ddio/20260622/photo.png",
    "mimeType": "image/png",
    "size": 12345,
    "source": "ddio",
    "sourceMessageType": "5"
  },
  {
    "type": "file",
    "fileName": "report.pdf",
    "localPath": "C:/Users/Administrator/.clawagent/uploads/report.pdf",
    "mimeType": "application/pdf",
    "knowledgeDocumentId": "..."
  }
]
```

附件处理规则：

- 图片：按上面的 vision 规则处理。
- 文件：继续走后台现有文件解析和知识库链路，不直接把原始文件塞进模型 payload。
- 附件解析后的模型补充上下文写入 `metadata.attachments.modelContext`。
- 若只有文件没有文字输入，知识库增强会按附件文档做摘要式上下文补充。

## 4. 系统意图和统一确认

系统意图配置位于 `claw-agent-intent/src/main/resources/clawagent/intents/system-intents.yml`。通道消息进入 `ChannelRouter` 后，会先检查当前会话是否存在待确认操作，再进行系统意图匹配；未命中时才进入普通 Agent Runtime。

内置意图覆盖：

- 会话指令：`/clear`、`/compact`、`/context`、`/status`、命令列表。
- 计划流程：生成计划、确认计划、取消计划。
- 工作区查看：只允许查看当前/最近工作区，不开放 IM 侧切换工作区。
- 文档流程：文档列表、下载提示、附件文档总结、附件文档问答、知识库检索。

风险交互规则：

- `risk: low`：命中后直接执行 handler 或直接进入模型上下文。
- `risk: medium`：创建 `PendingAction`，用户回复 `确认执行` 后执行。
- `risk: high`：创建 `PendingAction`，用户必须回复完整确认文本，例如 `确认执行：工具调用 builtin.execute.command`。

统一 PendingAction 类型：

- `INTENT_CONFIRMATION`：系统意图确认。
- `TOOL_APPROVAL`：Runtime 工具审批。
- `PLAN_APPROVAL`：计划确认。

文档意图不单独实现文件解析。通道或后台入口只需要把附件写入 `metadata.attachments`，现有 `AttachmentService` 会解析文件并写入知识库，`KnowledgeService` 再按 `knowledge.intent` / `knowledge.scope` 给模型补充上下文。

## 5. Feishu 出站消息类型

Feishu 出站仍复用统一 Channel 回写链路，通过 `outboundMessageType` 控制消息类型：

```yaml
clawagent:
  channels:
    configs:
      feishu:
        defaultAccount: main
        accounts:
          main:
            name: 主助手 - 飞书机器人
            enabled: true
            approvalMode: auto
            appId: ${FEISHU_MAIN_APP_ID:}
            appSecretEnv: FEISHU_MAIN_APP_SECRET
            outboundMessageType: text
```

支持值：

- `text`：发送普通文本。
- `post` / `markdown` / `rich_text`：发送飞书富文本 post。
- `image`：发送图片，优先使用 `feishu.imageKey` / `imageKey`；没有 key 时从 `metadata.attachments` 的图片 `localPath` 上传后发送。
- `file`：发送文件，优先使用 `feishu.fileKey` / `fileKey`；没有 key 时从 `metadata.attachments` 的文件 `localPath` 上传后发送。
- `card` / `interactive`：发送飞书卡片，优先使用 `feishu.cardJson` / `cardJson`；没有卡片 JSON 时用模型回复文本生成默认卡片。
- `attachments` / `multimodal`：先发送文本，再按 `metadata.attachments` 顺序发送图片和文件附件。
- `auto`：有附件时按 `attachments`，有卡片 JSON 时按 `interactive`，否则按 `text`。

附件 JSON 示例：

```json
[
  {
    "type": "image",
    "fileName": "photo.png",
    "localPath": "C:/Users/Administrator/.clawagent/media/inbound/feishu/photo.png",
    "mimeType": "image/png"
  },
  {
    "type": "file",
    "fileName": "report.pdf",
    "localPath": "C:/Users/Administrator/.clawagent/uploads/report.pdf",
    "mimeType": "application/pdf",
    "fileType": "stream"
  }
]
```

如果已经有飞书平台 key，也可以不上传本地文件：

```json
[
  {"type": "image", "imageKey": "img_xxx"},
  {"type": "file", "fileKey": "file_xxx"}
]
```

卡片 JSON 可放在 `metadata.feishu.cardJson` / `metadata.cardJson`，内容会作为飞书 `interactive` 消息的 `content` 发送。

## 6. DDIO 通道配置

### 6.1 application.yml 配置方式

```yaml
clawagent:
  channels:
    configs:
      ddio:
        defaultAccount: main
        accounts:
          main:
            name: DDIO Bot
            enabled: true
            approvalMode: ask
            appId: ${DDIO_MAIN_APP_ID:}
            appSecretEnv: DDIO_MAIN_APP_SECRET
            baseUrl: ${DDIO_MAIN_BASE_URL:https://192.168.0.180:10443}
            channel.ddio.chatScene: user
```

说明：

- `appId`：DDIO 开发者 ID / 应用 ID。
- `appSecretEnv`：保存应用密钥的环境变量名，避免明文写入配置文件。
- `baseUrl`：DDIO 平台 BaseURL，例如 `https://192.168.0.180:10443`。
- `channel.ddio.chatScene`：出站会话类型，常用值为 `user`；群或频道场景可用 `group`。

### 6.2 channels.json 配置方式

运行目录为服务启动目录时，文件路径为：

```text
.clawagent/channels/channels.json
```

示例：

```json
{
  "channels": {
    "ddio": {
      "defaultAccount": "main",
      "accounts": {
        "main": {
          "name": "DDIO Bot",
          "enabled": true,
          "approvalMode": "ask",
          "appId": "4611686027040362507",
          "appSecretEnv": "DDIO_MAIN_APP_SECRET",
          "baseUrl": "https://192.168.0.180:10443",
          "channel.ddio.chatScene": "user"
        }
      }
    }
  }
}
```

默认账号会注册成通道 ID `ddio`。非默认账号会注册成 `ddio-账号名`。

### 6.3 DDIO 公众号回调地址

服务端 DDIO 兼容回调入口：

```text
GET  /ddio/message
POST /ddio/message
```

`GET /ddio/message` 用于平台验证，返回：

```json
{"code":0,"msg":true}
```

`POST /ddio/message` 用于接收消息。服务会先返回 ACK，再异步进入 Agent，避免 DDIO 平台因响应慢而重复推送导致重复执行。

ACK 格式：

```json
{"code":0,"msg":true,"duplicate":false}
```

重复 messageID 会在 10 分钟内被去重，并返回：

```json
{"code":0,"msg":true,"duplicate":true}
```

如果服务部署在 `http://example.com`，则公众号平台回调 URL 配置为：

```text
http://example.com/ddio/message
```

## 7. DDIO 消息和媒体处理

DDIO 文本出站使用接口：

```text
POST {baseUrl}/platform/api2/message/send
```

请求体格式：

```json
{
  "receTargetID": "openID-or-groupID",
  "messageType": 2,
  "message": "文本内容",
  "chatScene": "user"
}
```

HTTP 请求使用共享 `AgentHttpClient`，并启用 `ignoreSsl(true)`，可访问内网自签名 HTTPS BaseURL。

DDIO 入站媒体消息会统一转成 `metadata.attachments`：

- `messageType=5`：图片，附件类型为 `image`。
- `messageType=6`：文件，附件类型为 `file`。
- `messageType=89`：视频，附件类型为 `video`。

媒体下载后会在本地解密并保存到 `.clawagent/media/inbound/ddio/yyyyMMdd/`，随后交给统一附件链路处理。

## 8. 本地编译命令

本项目需要 JDK 17 编译。当前 Windows 环境如果默认 `JAVA_HOME` 是 JDK 8，需要先切到 JDK 17。

```powershell
$env:JAVA_HOME='D:\tools\Java\64\jdk17.0.7'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean compile -DskipTests
```

前端类型检查：

```powershell
cd claw-agent-admin
npx tsc -b
```
