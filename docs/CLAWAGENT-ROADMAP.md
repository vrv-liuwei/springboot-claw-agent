# ClawAgent 后期计划

## 状态说明

- `[x]` 已完成并通过编译。
- `[~]` 已有骨架或占位实现。
- `[ ]` 未完成。

## 2026-06-12 进度纠偏（按当前代码）

- **已完成（可验证）**：核心执行链路（会话/任务/步骤/SSE）、内置 execute+process 工具链、文件写入备份+回滚、审批阻塞/恢复、主要管理台主界面已打通。
- **已完成（文件审查）**：文件审查后端去重、状态归一、任务事件扫描窗口、右侧详情关闭状态、历史消息恢复和局部 rollback 已有实现和单测/编译验证；删除-only hunk 已支持插回备份行段，多 hunk 支持逐块回滚；长任务场景已用 `FileChangeReviewSupportTest` 覆盖同文件 1200 次变更只展示最新版本。
- **已完成（恢复能力）**：checkpoint/resume 已具备运行时恢复机制和用户可见入口（todo 悬挂点 + CONTINUATION_REQUIRED + 恢复上下文注入 + resume-state API + 聊天页继续执行入口 + checkpoint/剩余 Todo 展示）；继续任务会继承源任务的项目目录、权限模式、知识库和附件引用，并在恢复卡片和审计页展示项目目录、恢复模式和恢复策略。多次 continuation 时，运行时恢复点选择已统一为优先 running、其次 failed、最后 pending，并写入 `resumeMode/resumeInstruction`。
- **已完成（会话上下文指令接口）**：已新增 `/clear`、`/compact`、`/context`、`/status` 对应的会话级 API，并在聊天输入框接入斜杠命令面板。`/clear` 会设置新的上下文开始时间，后续模型不再读取该时间前的会话消息；`/compact` 会生成会话摘要并作为后续提示词上下文继续注入；`/context` 和 `/status` 可查询上下文版本、活跃消息、Token 估算、工作区、权限、MCP、工具和 Todo 概况。
- **已完成（本地部署前置）**：本地配置页、Setup Wizard 第一版、本地健康检查、敏感路径配置、一键启动脚本和 Windows 启动链路已落地；Linux/macOS 启动脚本因当前无对应机器暂缓实机验收，干净机器 10 分钟配置体验后续做真实安装验证。

## 剩余核心任务数量（按当前代码）

- **P0/P1/P4/P5 主链路**：已完成，后续以真实任务使用反馈和小修为主。
- **P2 文件审查体验**：核心能力已完成。删除-only hunk 已支持插回备份行段，多 hunk 当前按逐块回滚处理；同文件大量变更只展示最新版本已有测试覆盖。
- **P3 后台进程**：Windows 已完成本机验收；Linux/macOS 进程树停止和服务就绪实机验证因当前无对应机器暂缓，不阻塞 Windows 本地行动闭环。
- **M3A 自动化**：失败重试、退避、失败后暂停、运行历史耗时、Token 和工具链路聚合已落地；后续只保留更细的统计报表增强。
- **M5 管理台**：Channel/Auth/Device 基础页面已接入；内置能力管理、能力权限配置、成本规则页面和全局 Audit Log 查询页已接入。能力权限页已补本地权限字段编辑入口，剩余低优先级是本地用户页和企业维度权限矩阵。
- **M6/M7 后续扩展**：checkpoint/resume 已增强跨多次 continuation 的恢复点选择；向量记忆当前 SQLite + JVector 已完成，外部向量库 SPI/实现暂缓。

## 2026-06-16 开发进化队列（按当前代码纠偏）

- **本次任务收口（Channel 分层）**：本轮只统计 Channel 架构拆分，不把管理台其它业务拆分计入 Channel 完成标准。当前 Channel 目标已收口为：领域对象在 `claw-agent-core`，SPI 在 `claw-agent-spi`，通用注册/路由/会话映射、飞书/钉钉内置官方 HTTP adapter、飞书长连接 SDK 启动入口和钉钉 Stream SDK 启停入口在 `claw-agent-channel`，`claw-agent-server` 只保留 Channel HTTP Controller。飞书/钉钉已建立平台包：`channel.feishu.FeishuInboundAdapter/FeishuChannelAdapter/FeishuOutboundClient/FeishuStreamClient` 和 `channel.dingtalk.DingtalkInboundAdapter/DingtalkChannelAdapter/DingtalkOutboundClient/DingtalkStreamClient`；2026-06-18 已新增 `ChannelRuntimeAdapter` 和 `ChannelAdapterRegistry`，内置飞书/钉钉/DDIO 也通过同一注册表加载，`ChannelInboundPayloadAdapter`、`ChannelOutboundClient`、`ChannelStreamClientManager` 和 `FileChannelRegistry` 不再按平台写死分发。`FileChannelRegistry` 已支持 OpenClaw 风格 `channels.<type>.accounts` 对象/数组配置读取，并展开为统一 `ChannelDefinition`；`application.yml` / `.clawagent/config/clawagent.yml` 已支持 `clawagent.channels.definitions` 和 `clawagent.channels.configs` 配置 Channel，且同一个 channelId 下 YAML 显式配置优先于 `.clawagent/channels/channels.json`；`clawagent.channels.adapter-path` 已支持启动时扫描外部 adapter jar 并通过 `ServiceLoader<ChannelRuntimeAdapter>` 加入同一注册表；2026-06-22 已新增 adapter 诊断、重新扫描和 jar 导入 API，管理台可查看每个 type 的实现类、来源、代码位置、最终生效状态，可手动重新扫描外部 jar，也可上传 `.jar` 到第一个 adapter-path 后自动刷新；图片/文件/Markdown/卡片类入站已先转换为可审计占位文本和 metadata；全量事件映射、媒体下载、卡片富渲染、外部 adapter jar 列表/删除管理、Stream registry 热切换、OpenClaw 分组结构写回管理和真实开放平台联调仍作为后续 adapter 任务，不在 server 内堆业务。
- **Channel YML 优先级补充**：2026-06-18 已确认 YML 直配凭证优先于环境变量兜底，YML 显式 Channel 优先于 `.clawagent/channels/channels.json`。飞书/钉钉兼容入口 `/api/v1/channels/feishu/inbound`、`/api/v1/channels/dingtalk/inbound` 已改为优先选择启用的 YAML 默认账号；DDIO 兼容入口 `/ddio/message` 已改为优先选择启用的 YAML DDIO 账号，避免多账号配置时误落到内置禁用占位通道。
- **Channel 真实接入体验补充**：2026-06-18 已确认飞书使用官方 `oapi-sdk` 长连接，钉钉使用官方 `dingtalk-stream` SDK。钉钉 health 已兼容 Stream-only 配置：`connectionMode=stream` / `dingtalk-stream` 时检查 `clientId/clientSecret` 或 `appKey/appSecret`，不再误要求自定义机器人 `webhookUrl`。管理台 Channel 页已补飞书直填 `appId/appSecret`、钉钉 Stream 直填 `clientId/clientSecret/appKey/appSecret` 和 DDIO `appId/appSecret/baseUrl` 专用字段，DDIO 出站测试已明确 `receTargetID` 目标并可配置 `channel.ddio.chatScene=user|group`；DDIO 出站已从通用门面传入的 `HttpClient` 走可测试路径，并保留默认内网自签 HTTPS 兼容；保存仍统一落到 Channel metadata；YAML 管理的 Channel 已标记 `channel.source=yaml/channel.readOnly=true`，管理台显示 YAML 标签并禁用保存，避免被 `channels.json` 静默覆盖后又被 YAML 优先级覆盖。Channel 列表已展示多账号 `channel.accountId` 和 `channel.isDefaultAccount` 标识，能直接区分 YAML/OpenClaw 风格配置展开出的主账号和其它账号；Adapter 运行时区已展示内置和外部 adapter 的生效状态，并支持导入和重新扫描外部 jar，便于排查自有 IM jar 是否接管指定 type。
- **Server Controller 拆分进度**：`Log/Audit/MCP/Skill/Config/Session/Process/Knowledge/Memory/Channel/Auth/Device/AgentOrchestration/TaskSearch/App` 已在独立 controller 包下；本轮继续拆出 `HealthController`、`AttachmentController`、`ToolController`、`TodoController`、`AutomationController`、`TaskExecutionController`、`ConfigController`、`TaskReviewController`，并把附件、自动化、审批、恢复、文件审查、runtime config、任务审计和开发摘要相关请求/响应 DTO 下沉到 `server.dto`。原 `AgentController` 已迁移为 `server.service.AgentConsoleService`，不再注册 HTTP 路由；当前它作为 runtime config、任务审查、文件审查、审计和开发摘要的过渡编排服务保留业务 helper，后续如继续拆分应优先把“任务审查/开发摘要”和“本地配置”两组 helper 正式迁移到独立 service 类。
- **Channel**：已按模块边界拆分：`claw-agent-core` 放 Channel 领域对象，`claw-agent-spi` 放 `ChannelRegistry/ChannelAdapter`，`claw-agent-channel` 放默认注册表、入站路由、会话映射、飞书/钉钉内置官方 HTTP adapter、文本出站客户端和 SDK Stream 管理器，`claw-agent-server` 只保留 HTTP Controller。内置 `webui/api/feishu/dingtalk` 模板；外部 IM 项目可先通过 `/api/v1/channels/inbound` 或 `/api/v1/channels/{channelId}/inbound` 接入。2026-06-17 已新增 `ChannelInboundPayloadAdapter`、`ChannelOutboundClient` 和 `ChannelStreamClientManager`：飞书支持明文 URL challenge、Verification Token、Encrypt Key AES-256-CBC 解密、文本事件入站、`tenant_access_token` 内存缓存、`im/v1/messages` 文本回写、基础 post 回写和 SDK 长连接 `P2MessageReceiveV1` 入站；钉钉支持自定义机器人文本入站解析、配置 Secret 后的 timestamp/sign 入站校验、webhook HMAC-SHA256 加签、文本/markdown 回写和 Stream SDK 通用事件入站，并会先抽取常见文本/会话/用户字段再转入 ChannelRouter。2026-06-18 已把平台实现从通用门面拆到平台包：`channel.feishu.FeishuInboundAdapter` 负责飞书 HTTP 入站解密、Verification Token 校验、URL challenge、文本事件映射以及图片/文件/卡片/富文本占位 metadata，`channel.feishu.FeishuChannelAdapter` 负责飞书入站 SPI、出站回写、连通性检查和 Stream 启动适配，`channel.feishu.FeishuOutboundClient` 负责飞书 token 缓存、文本/post 发送和连通性检查，`channel.feishu.FeishuStreamClient` 负责飞书长连接启动和 `P2MessageReceiveV1` 事件转发；`channel.dingtalk.DingtalkInboundAdapter` 负责钉钉 HTTP 入站签名校验、文本事件映射以及图片/文件/Markdown/卡片占位 metadata，`channel.dingtalk.DingtalkChannelAdapter` 负责钉钉入站 SPI、出站回写、连通性检查和 Stream 启动适配，`channel.dingtalk.DingtalkOutboundClient` 负责钉钉 webhook 加签、文本/markdown 发送和连通性检查，`channel.dingtalk.DingtalkStreamClient` 负责钉钉 Stream 启动、停止和通用事件转发。2026-06-18 已补 `ChannelRuntimeAdapter` + `ChannelAdapterRegistry`，入站、出站、连通性检查、Stream 启停和内置模板都按 adapter 注册表分发，启动时会扫描 `clawagent.channels.adapter-path` 并通过 `ServiceLoader<ChannelRuntimeAdapter>` 加载外部 jar adapter；2026-06-22 已补 adapter 诊断、普通外部 jar 重新扫描和 jar 导入入口，重新扫描或导入后普通入站、出站和连通性检查立即使用新注册表，已启动 Stream 长连接仍需要停止/重启进程切换；`channels.json` 和 `application.yml` 均已支持读取 OpenClaw 风格 `channels.<type>.accounts` / `clawagent.channels.configs` 多账号配置，对象和数组账号都会展开为统一 `ChannelDefinition`，平台级配置进入 metadata，账号级配置覆盖同名 key，YAML 显式配置优先级高于 `channels.json`；管理台 Channel 页已补飞书/钉钉专用配置字段、连通性检测、Stream 启停入口、飞书出站 text/post 选择、钉钉出站 text/markdown + markdownTitle 配置、手动出站测试、adapter 重新扫描和 jar 导入入口；出站测试会展示平台 HTTP 状态和响应摘要，保存仍映射到 metadata，避免用户手写关键 key。全量事件类型映射、媒体下载、卡片富渲染、外部 adapter jar 列表/删除管理、Stream registry 热切换、OpenClaw 分组结构写回管理和真实开放平台联调仍未完成。
- **ApprovalPolicy / PermissionPolicy**：当前已补核心策略对象，`ApprovalPolicy` 已承接 `permission-mode/approvalMode/approvedToolIds/allowHighRiskTools` 并被默认 Guard 使用；`PermissionPolicy` 已沉淀 allowed roots、敏感路径和默认 cwd 的领域表达。运行配置快照已返回 `policy` 解释视图，管理台“能力权限配置”会展示当前生效审批模式、allowed roots、默认目录、有效规则和后续增强项。2026-06-17 已新增 `/api/v1/config/policy` 专用保存入口，能力权限页保存时只写审批/权限字段，不再借用模型配置保存接口；保存后会写入 `policy.config_updated` 全局审计事件，记录审批模式、白名单数量、allowed roots 数量、敏感路径规则数量、workspace 和默认 shell 的前后变化；策略快照新增 `resolutionOrder`，管理台展示 local/channel/task/agent-isolation/tool-enforcement 的解析顺序和当前状态。Channel 入站任务会写入 `policy.approval.source=channel:<channelId>`、`policy.approval.scope=channel` 和解析顺序；只读子 Agent 会写入 `policy.approval.source=agent-isolation:read-only`、`policy.approval.scope=agent`、解析顺序和覆盖原因，便于后续审计与跨任务检索。已新增 `ApprovalPolicyResolution` 作为轻量策略解析对象，统一输出最终策略、来源、作用域、解析顺序、覆盖原因和 metadata 冲突提示；`ToolExecutionGuard` 和管理台策略快照已改走该对象。2026-06-17 能力权限页已补“本地权限规则”编辑区，可在同一页维护 workspace、默认 shell、allowed roots 和敏感路径规则，并继续复用 `/api/v1/config/policy` 保存。仍缺 Channel/User/Agent 维度完整合并规则和企业权限矩阵。
- **Tool/Skill/MCP 管理页**：当前已有列表、导入、连接、启停、详情、删除和 MCP autoApprove；MCP 已补显式 update/delete 接口，详情弹窗支持 JSON 编辑保存，并在详情里提供 `autoApprove` 显式编辑区，审批白名单不再只能手写 JSON；Skill 已补显式 `PUT /api/v1/skills/{skillId}` 更新接口，详情弹窗保存 Manifest 时不再复用安装接口，后端会要求目标 Skill 已存在并保留原安装时间；系统工具页已复用内置能力权限配置，可维护本地权限模式和高危工具白名单。2026-06-17 已补 Skill 安装/导入/更新/删除/启用/停用和 MCP 注册/导入/更新/删除/连接/断开全局审计事件，事件只记录安全摘要，不落完整 manifest、命令、env 或 headers；系统工具列表已支持在工具行和详情弹窗直接加入/移出审批白名单，保存走 `/api/v1/config/policy` 专用策略接口，不再从技能页误走模型配置保存；能力权限页已补 workspace、默认 shell、allowed roots、敏感路径的字段级编辑入口。仍缺 Channel/User/Agent 维度审批矩阵。
- **Task/Step 跨任务检索**：已补后端只读检索入口、前端 API 方法和管理台会话页入口：`/api/v1/tasks/search` 支持按关键词、状态、渠道、用户、会话和任务 metadata 检索历史任务；`/api/v1/steps/search` 支持跨任务检索工具步骤、失败步骤和输出片段，并已补 `toolId/riskLevel` 过滤，方便定位某类工具调用和高危/未知风险步骤；页面支持任务/步骤切换、基础筛选、筛选条件本地保存、命中片段高亮、任务/步骤聚合摘要、风险列展示和点击结果打开任务详情。后续只保留更复杂的历史趋势和跨任务关联分析。
- **多 Agent / 子 Agent**：已补最小父子任务编排 API：父任务可通过 `/api/v1/agents/{parentTaskId}/subtasks` 派生只读子 Agent，子任务 metadata 记录 `agent.parentTaskId/rootTaskId/role/isolation`，并继承项目/知识库/附件上下文但清空高危批准；`ToolExecutionGuard` 会硬拦截只读子 Agent 的非 low 风险工具。管理台任务详情摘要已接入子 Agent 列表，可查看父任务派生出的子任务并点击进入子任务详情；2026-06-17 已新增只读子 Agent 创建入口，用户可在任务摘要中填写角色和任务说明直接派生子任务。2026-06-17 已将子任务列表从 `searchTasks(parentTaskId)` 模糊检索收敛为 `TaskStore.findSubTasks(parentTaskId)` 明确存储接口，并覆盖内存和 SQLite 两种实现；2026-06-22 已新增 `/api/v1/agents/{rootTaskId}/graph` 轻量编排图接口，管理台任务摘要会展示父子任务计数、运行/等待/完成/失败状态和父子边关系；子任务 metadata 已记录 agent-isolation 策略来源、解析顺序和覆盖原因。仍缺独立 worker 进程隔离、并行调度器、任务拆分策略和 Channel/User/Agent 维度策略矩阵。
- **Channel/Auth/Device**：Channel API 骨架和管理台基础页已启动，可查看、编辑、删除 WebUI/API/飞书/钉钉模板并提交通用入站测试；Channel 注册表已补 `jackson-datatype-jsr310` 依赖，保存带 `Instant` 时间戳的配置不会再因 Jackson 缺少 Java Time 模块失败；Auth 已补本地 API Token 生命周期管理页、后端生成/撤销/列表接口和默认关闭的 API Token 鉴权拦截器，token 明文只创建时返回、落盘仅保存哈希，鉴权成功会更新 `lastUsedAt`；Device 已补本地登记、心跳、撤销接口和管理台基础页。2026-06-17 已补 Channel 创建/更新/删除、API Token 创建/撤销、Device 登记/心跳/撤销的全局审计事件，事件只记录安全摘要，不保存 token 明文、hash、Channel metadata 或设备 metadata；ChannelRouter 入站链路会用 Channel 配置覆盖消息自带审批模式，并记录 channel 级策略来源，避免外部 IM 请求绕过本地策略。2026-06-17 飞书/钉钉已作为 `claw-agent-channel` 内置 adapter：飞书覆盖 Webhook URL 校验、Verification Token、Encrypt Key 解密、文本回写和长连接文本消息入站；钉钉覆盖自定义机器人 webhook 加签、文本回写和 Stream 通用事件入站。本地用户、设备配对码、Token/Device 权限绑定和真实平台联调仍未完成。
- **桌面端壳**：`claw-agent-app` 已完成 Electron MVP，后续只做发布级能力（内置 JRE 验收、安装包签名、自动更新、托盘、崩溃恢复），不再按“桌面壳未完成”统计。

## 当前优先级顺序

1. [x] 短期记忆与长期记忆重新设计。已落地 global/channel/session 长期记忆、本轮上下文构建、候选提炼、命中记录和管理台维护。
2. [~] 正式管理台持续优化。后台新增的业务功能必须同步补充管理台页面，旧原生 Console 暂时保留。
3. [~] 智能体定时任务与自动化。单机版任务配置、启停、立即执行、运行历史、失败重试、退避、失败后暂停、耗时/Token/工具链路聚合已完成；更细统计报表未完成。
4. [x] Shell/cmd/PowerShell 工具。已接入本机 execute 工具、动态风险分类、审批 metadata、输出事件记录和管理台风险展示；兼容模型把整条命令写入 `command` 字段的情况；删除/覆盖/脚本/安装等高危操作继续要求审批；同步 execute 负责有超时的前台命令，运行本地服务走 process 后台进程工具。
5. [x] Skill 目录风格和加载逻辑调整。每个 Skill 独立目录保存，加载器按目录扫描并注册 manifest、入口文件、scripts、assets、references、lib。

## 下一步开发计划（2026-06-12，CodeGraph 校验版）

目标：继续把 ClawAgent 收敛成能稳定完成真实本地开发任务的 Local Action Agent。当前 execute/process、文件审查、rollback、resume、开发摘要、审批主链路都已有落点；下一步不重复造轮子，优先补“项目可信、任务可恢复、结果可验证、页面可解释”。

### P0：项目工作区可信化

- [x] 按项目目录配置测试命令映射。保留全局测试命令，同时支持 `projectPath -> testCommands`；本地配置页用 `项目路径 => 验证命令` 维护映射，开发摘要按任务 metadata/命令 cwd 做最具体项目匹配。
- [x] 最近项目选择器。聊天页项目目录输入保留自由输入，同时从默认工作区和最近项目生成下拉选项，支持快速切换；发送任务时会通过 `/api/v1/config/local/recent-projects` 把当前项目目录写入 `local.recent-projects`，避免只存在浏览器 localStorage。
- [x] 工作区忽略规则。`local.ignore-patterns` 支持配置 `.gitignore` 风格 glob；filesystem 搜索、文件审查和开发摘要默认避开 `.git/.clawagent/node_modules/target/build/dist/.idea/.vscode` 等目录，显式读取单个文件不受影响。
- [x] 项目目录确认交互。当模型找不到 runnable project 或存在多个候选项目时，execute/process 工具返回 `requiresProjectConfirmation: true`、reasonCode、requestedCwd 和 candidateProjects；管理台工具详情会高亮“需要确认项目目录”。存在候选目录时，用户可直接点击“使用并继续”，前端会保存该目录并通过 resume 流程把它写入本次任务 metadata。继续执行历史任务时，后端会继承源任务的 `activeProjectPath/projectPath/workspace.projectPath`，避免恢复任务丢失项目目录后回退到默认 cwd。
- 验收：用户选择一次项目目录后，后续刷新、继续任务、跑测试、开发摘要都能使用同一个项目上下文。

### P1：开发任务闭环稳定化

- [x] 测试/编译命令策略。开发摘要新增验证计划，按项目配置、全局配置、项目文件自动检测生成验证命令，标记来源、cwd、是否已执行、最近状态和退出码；当前先提示/展示，不默认盲目自动执行。
- [x] 失败重试策略。开发摘要新增结构化失败分析，按项目目录、编译、测试、端口、权限、认证、路径、超时、网络、依赖等分类，标记是否可重试、建议重试次数和下一步修复动作；运行时仍保留重复失败阻断，避免盲目重复执行。
- [x] 最终结果标准化。开发摘要新增最终结果对象，统一展示任务结果、验证状态、是否可提交、文件/命令/测试/失败计数、剩余风险和下一步动作。
- [x] Git 视图补强。开发摘要新增 Git 审查视图，汇总已执行的 `git status/diff` 结果；未执行时只展示建议命令和下一步，不自动运行、不自动 commit。
- 验收：能完成一个真实代码修改任务，失败后能继续修复，成功后能给出可审查的变更和验证结果。

### P2：文件审查体验补齐

- [x] diff/rollback 交互验收。同一文件多次修改后，后端文件审查接口已按规范化路径只返回最新版本，并返回被折叠的历史修改次数；`FileChangeReviewSupportTest` 覆盖了 Windows/Unix 路径归一、rollback/failed 状态、空路径忽略、“先折叠最新路径再分页”和同文件 1200 次变更只展示最新版本。`fileChangesForTask` 内部会扫描最多 5000 条最近 task event，再对最终文件审查列表按 limit 截断，避免长任务里文件变更被后续普通工具调用挤出窗口。文件列表和开发摘要会标记“最新/已回滚/失败”。右侧文件审查详情关闭时会清理旧详情态，顶部按钮只反映真实打开状态；重新打开时由用户主动选择当前文件或最新文件。历史消息恢复文件审查时，有变更的 task 写入缓存，空结果只做 30 秒短时标记，避免事件稍后可见时永久不显示审查列表。手动 rollback 和选中行 rollback 后会刷新文件审查列表，并按同路径最新变更恢复选中态，同时触发任务开发摘要刷新。
- [x] 文件变更与 Todo 关联。`FileChangeView` 已带出 `todoId/todoOrder/todoTitle`，文件审查列表、审查详情和开发摘要文件表展示产生该变更的 Todo 来源。
- [x] 文件审查筛选。文件审查列表支持按全部/新增/修改/回滚/失败筛选；后端会把失败的文件写入/回滚尝试解析成 `failed` 变更记录，便于用户定位失败文件操作。
- [x] patch 级撤销。当前已支持在文件审查 Diff 视图中回滚当前变更块或手动选中行范围：前端用 Monaco `getLineChanges()` 识别当前 hunk，普通修改块按当前文件行段替换，删除-only hunk 用备份文件原始行段插回相邻位置；后端会校验普通选中内容仍与前端看到的一致，局部回滚写回已复用 `builtin.filesystem.write_file` 的 allowed-roots、blocked-patterns 和备份逻辑，避免绕过 filesystem 安全边界。多 hunk 当前支持逐块回滚，批量回滚和更细冲突提示后续增强。
- 验收：用户能在历史会话里稳定看到文件审查，知道最新版本是哪一个，并能撤销本轮任务改动。

### P3：后台进程与启动诊断

- [x] 启动失败摘要。process 列表、日志详情和任务开发摘要已根据命令、日志尾部和端口监听状态生成诊断，覆盖端口占用、配置错误、依赖缺失、认证/权限失败、路径不存在、进程早退等常见原因。
- [x] 端口与健康检查。进程面板已显示端口监听状态、最近日志和诊断摘要；`builtin.process.start` 支持 `healthUrl/healthCheckUrl`，服务端会短超时探测健康 URL，并在进程面板、日志详情和任务开发摘要中展示健康状态。
- [x] 进程关联任务。新启动的后台进程已记录 taskId/sessionId/projectPath，进程面板展示来源，任务开发摘要按 taskId 聚合本任务启动过的后台进程。
- [~] 停止策略验收。`builtin.process.stop` 已改为先停止子进程再停止父进程，普通停止超时后强停残留；管理台停止接口只在确认进程树退出后移除托管记录，未退出时保留记录供继续强停或查看日志。新增 `ProcessStopToolTest` 用真实测试 JVM 子进程验证 Windows 当前环境下 stop 会停止进程并移除托管记录；Linux/macOS 因当前没有对应机器，暂缓到有环境时实机验收。
- 验收：启动本地服务不再卡住会话，失败时用户能在页面看懂原因，成功时能看到 pid/端口/日志。

### P4：配置和本地部署前置

- [x] 本地配置页收敛。模型、workspace、allowed roots、默认 shell、权限模式、测试命令、最近项目、忽略规则和敏感路径已集中到配置页；保存时会同步 execute/filesystem 的 `ALLOWED_ROOTS`、`DEFAULT_CWD` 和敏感路径规则，并与 Setup Wizard 共用同一套本地行动配置状态。
- [x] Setup Wizard 第一版。管理台首次进入时会在本地行动能力未配置完成且用户未跳过时弹出初始化向导；本地配置页保留同一套紧凑部署向导，按工作区、模型、权限、健康检查、MCP/Skill 展示配置进度，并复用保存配置、普通检查和模型深度检查。向导会把本地健康检查中的 `warning/error` 项直接列为“需处理项”，用户能看到具体是工作区、模型、allowed roots、默认 Shell、敏感路径还是 MCP/Skill 没就绪。
- [x] 本地健康检查。已新增 `/api/v1/config/local/health`，管理台“本地配置”页和首次 Setup Wizard 按需加载，检查 workspace、配置文件、SQLite、模型配置、execute/process/filesystem/todo 工具、allowed roots、执行目录、默认 shell、权限模式、敏感路径、`.clawagent/backups/filesystem` 文件备份目录、`.clawagent/processes` 后台进程表目录、MCP 和 Skill；页面支持“深度检查”，通过 `deep=true` 真实请求默认模型验证连通性，普通检查不触发外部模型请求。
- [x] 一键启动脚本整理。根目录新增 `start-clawagent.ps1` 和 `start-clawagent.sh`，会检查 Java/Maven/npm、端口占用和常见 API Key 环境变量，默认构建管理台前端、打包服务端并启动 server/admin；后台启动模式会等待 Health API 变为 `UP`，超时后提示日志位置；README 已切换为脚本优先的快速启动说明。Windows 已用 `17991` 临时端口完成后台启动实测：Health API 返回 `UP`，Admin Console 返回 HTTP 200，随后按端口停止进程并确认服务关闭。
- 验收：Windows 本地脚本启动链路已实测；Linux/macOS 脚本已做语法检查，因当前无对应机器暂缓真实后台启动验收。新环境能否 10 分钟内完成配置，后续在干净机器上走一次 Setup Wizard 和真实任务验证。

### P5：轻量安全治理

- [x] 自定义审批策略持久化。`local.permission-mode` 和 `local.approved-tool-ids` 已进入本地覆盖配置；管理台本地配置页可保存四种权限模式和 custom 工具白名单，聊天框审批控件会同步到配置 draft，任务 metadata 仍记录本次实际审批模式和工具列表。
- [x] 敏感路径分级。`local.sensitive-path-patterns` 已进入本地配置；filesystem 会把这些模式同步为 `BLOCKED_PATTERNS` 直接拦截，execute 会把命中 `.env`、key、pem、p12、pfx、ssh、git 内部目录的命令升为 high risk 并进入审批。
- [x] 审计查询。新增 `/api/v1/tasks/{taskId}/audit`，按任务聚合工具调用、审批、文件变更、rollback、命令输出、resume 恢复点和事件时间线；管理台任务详情新增“审计”页签，可直接回放本任务做过什么，包括从哪个源任务、哪个 Todo、什么恢复模式继续执行。
- [x] Prompt Injection Defense 最小版。Runtime 会在工具输入和工具输出中检测忽略指令、泄露系统提示词/密钥、绕过审批、隐藏审计等可疑内容，写入 `security.prompt_injection_detected` 审计事件并标记 task；后续非 low 工具会被 `ToolExecutionGuard` 强制转人工确认，避免在 auto/full 模式下静默继续高危动作。
- [x] 审批拒绝闭环。`WAITING_APPROVAL` 任务现在支持前端直接拒绝，后端通过 `/api/v1/tasks/{taskId}/approvals/{stepId}/reject` 唤醒同一个 task/step，记录 `tool.approval_rejected` 审计事件并终止本次高危工具调用。
- 验收：高危动作不会静默执行，用户能回看本地任务做过什么。

### 继续暂缓

- [~] 桌面端发布增强。`claw-agent-app` 已有 Electron 壳、内置 server 启动和 `/app/` UI；后续补内置 JRE 验收、安装包签名、自动更新、托盘和崩溃恢复。
- [ ] 企业多租户、API Token、RateLimit、OpenTelemetry、分布式 Worker。
- [ ] Qdrant/Milvus/PgVector 等多向量库后端；当前 SQLite + JVector 先够用。

## 近期执行拆解

1. 短期和长期记忆：已完成第一版实现；后续按 OpenClaw 可读 Markdown 方案增强长期记忆文件、每日过程记忆、候选晋升、去重、冲突处理、过期策略和更细质量评分。
2. 正式管理台：后台每新增一个业务 API，同步增加管理台页面或入口；当前已覆盖记忆、Shell/Process、审批/权限、审计、文件审查和成本规则，后续只补企业治理类页面。
3. 智能体定时任务与自动化：失败重试、退避间隔、失败后暂停、运行历史耗时/Token/工具链路聚合已完成；后续只做统计报表增强。
4. Shell/cmd/PowerShell 工具：先实现查看类命令免审批执行，删除、覆盖、格式化、脚本执行、依赖安装等破坏性操作进入用户确认；worker 隔离放到后续增强。
5. Skill 目录和加载逻辑：已调整为 `.clawagent/skills/<skillId>/` 独立目录，加载器按目录保留原始 Skill ID，并完整加载 `manifest.json`、`SKILL.md`、`scripts/`、`assets/`、`references/`、`lib/`。

## M1：可启动平台

- [x] Maven 多模块工程。
- [x] Java 17 编译配置。
- [x] `claw-agent-core` 核心领域对象。
- [x] `claw-agent-spi` 扩展接口。
- [x] `claw-agent-runtime` 同步执行链路。
- [x] `claw-agent-toolkit` 工具注册和内置工具。
- [x] `claw-agent-persistence-sqlite` SQLite3 任务/步骤持久化。
- [x] `claw-agent-memory` 本地记忆模块，包含 SQLite FTS5、JVector、RRF 混合检索和 Markdown 兼容层。
- [x] `claw-agent-spring-boot-starter` 自动配置。
- [x] `claw-agent-server` 独立 Spring Boot 服务，默认端口 `17891`。
- [x] 最小 Web Console。
- [x] REST API：health、submit task、assistant、task、steps、tools。
- [x] OpenAI 兼容模型客户端，默认可配置 DeepSeek。
- [x] LLM Planner：使用真实模型生成工具调用计划。
- [x] LLM ResponseGenerator：使用真实模型生成最终回答。
- [~] RuleBasedPlanner：保留为 `clawagent.model.mode=rule` 时的本地兜底。

## M2：模型与会话

- [x] 接入 Spring AI。已提供反射式 ChatClient 适配，业务应用引入 Spring AI 并配置 `clawagent.model.client=spring-ai` 后启用；默认仍使用 OpenAI 兼容 HTTP 客户端。
- [x] `ModelClient` SPI。
- [x] `EmbeddingClient` SPI。
- [x] OpenAI 兼容 Embeddings 客户端。
- [x] ReAct Planner。
- [x] LLM Tool Calling。
- [x] 流式输出。已支持任务事件 SSE 和 OpenAI 兼容模型 token/chunk 级 stream。
- [x] Session 表和 Session API。
- [x] Runtime 提交任务时自动创建或恢复会话。
- [x] 会话消息表。
- [x] 会话摘要生成。
- [x] 长期记忆自动候选提炼。默认只生成 `pending`，管理台审核后才进入模型上下文。
- [~] 长期记忆质量治理。已具备入库、检索、候选审核、启停、删除、命中记录、去重、合并、冲突标记、质量分和未命中降权指标；自动归档/过期仍需按配置阈值继续增强。
- [x] Token usage 记录。LLM 调用日志已有 token usage，并已提供会话/任务级聚合查询 API；管理台已支持配置模型成本规则并估算会话、任务和模型维度成本。

### M2-P1：短期与长期记忆设计

- [x] 短期记忆设计。Runtime 每轮构建统一 `runtime.memoryContext`，包含会话摘要、最近消息、Todo 状态和命中的 active 长期记忆。
- [x] 长期记忆设计。长期 scope 只启用 `global/channel/session`；`task` 仅作为运行时来源，不作为长期 scope。
- [~] 记忆摘要策略。会话摘要和候选提炼已接入；长网页、仓库文件、工具输出的统一摘要质量后续继续增强。
- [~] 记忆质量治理。已支持 pending/active/disabled/conflict/archived、启停、删除、命中记录、去重、合并、冲突处理、质量评分和未命中降权指标；自动过期归档后续按配置增强。
- [x] 记忆管理台页面。支持列表、搜索、新增、编辑、启用、禁用、删除、候选处理和命中查看。

#### M2-P1A：OpenClaw 风格记忆文件优化

目标：保留当前 SQLite FTS5 + JVector + RRF hybrid search 的检索能力，同时补齐人能直接查看和维护的 Markdown 记忆文件。数据库负责结构化管理和检索，Markdown 负责可读、可审查和可导出。

- [x] 长期记忆文件。为每个用户生成 `.clawagent/memory/{userId}/MEMORY.md`，只写入 `active` 的长期记忆，按 `global/channel/session` 分组展示，不写入 pending、disabled、archived。
- [x] 每日过程记忆。新增 `.clawagent/memory/{userId}/daily/YYYY-MM-DD.md`，记录当天候选、冲突、归档和 active 记忆，作为人工回溯材料。
- [x] 候选晋升规则。普通聊天不直接进入长期记忆；明确偏好、长期规则、稳定事实、反复出现的信息先进入 `pending`，用户审核后才变成 `active` 并写入 `MEMORY.md`。
- [x] 去重处理。新增内容入库前先按 content hash、语义相似度和 scope 判断是否重复；重复内容不新增长期记忆，只更新重复次数、更新时间或来源。
- [x] 合并处理。同一主题的多条相近记忆支持合并，保留来源记录和最近更新时间，避免上下文里出现多条意思相同的记忆。
- [x] 冲突处理。新候选和已有 active 记忆含义冲突时，不自动覆盖旧记忆；标记为 conflict，在管理台提示用户确认。
- [~] 过期和降权。已记录命中次数、最近命中时间、质量分、未命中天数和降权指标；自动归档规则后续按配置增强，避免误归档有效记忆。
- [x] 命中解释。记忆管理台展示记忆为什么被命中：检索来源、RRF 分、rank、scope、最近命中时间和命中任务。
- [x] Markdown 一致性。记忆新增、编辑、启用、禁用、删除后，重建 `MEMORY.md` 和当日 daily 文件，保证 DB、FTS、JVector、Markdown 内容一致。
- [x] 上下文注入规则。模型上下文只注入 topK active 记忆和必要短期上下文，不注入完整 Markdown 文件，不注入全部历史消息。

## M3：通用 MCP 与 Skill

- [x] `claw-agent-mcp` 通用 MCP 接入层。
- [x] MCP Server 注册表和管理 API。
- [x] MCP Server 配置本地保存，重启后自动加载注册配置。
- [x] 支持 stdio MCP。
- [x] 支持 HTTP/SSE/streamable HTTP MCP。已实现 HTTP/streamableHttp JSON-RPC 基础连接和老版 SSE endpoint/message 长连接。
- [x] MCP tools 映射为 `AgentTool`。
- [x] MCP resources/prompts 管理。已支持 list/refresh、resources/read、prompts/get。
- [x] `claw-agent-skill` 标准 Skill 包骨架。
- [x] Skill 本地安装、启用、禁用和列表查询。
- [x] Skill 声明工具自动加载到 `AgentToolRegistry`。
- [x] Skill 权限声明：manifest 已保存，HTTP/script/java executor 会在运行前校验对应权限。
- [x] Skill 脚本/HTTP/Java 插件执行器。已支持 document/http/script/java executor。
- [x] Skill 目录规范调整。每个 Skill 固定保存为 `.clawagent/skills/<skillId>/`，目录内包含 `manifest.json`、`SKILL.md`、`scripts/`、`assets/`、`references/`、`lib/` 等资源，不再把外部 Skill 折叠成通用 `imported-skill`。
- [x] Skill 加载逻辑调整。启动和刷新时按 Skill 子目录扫描，保留原始 Skill ID，完整加载脚本、资源、引用和 Java 插件 Jar。
- [x] 工具执行前置拦截点。Runtime 已接入 `ToolExecutionGuard`，默认高危工具需要 metadata 显式审批。
- [x] 内置 filesystem 工具。已支持读取文本、列目录、搜索文件、文件信息、受控写入；配置统一走 `clawagent.toolkit.tools.filesystem.env`，不依赖外部 MCP filesystem。
- [x] M3 管理台入口。新增 React + Vite 管理台源码模块 `claw-agent-admin`，构建后访问 `/admin/index.html`；保留原 `/console/index.html` 用于聊天调试。
- [x] MCP `autoApprove`。配置已接入工具风险等级：命中规则的 MCP tool 为 low risk，未命中默认 high risk。
- [~] MCP 连接健壮性。stdio、HTTP、SSE 主链路可用，已有 timeout、启动隔离、运行态按需重连、连接失败清理和刷新失败卸载旧工具；更复杂的心跳检测和会话级关闭后续增强。

## M3A：智能体定时任务与自动化

- [x] Automation 领域模型。区分一次性执行记录 `AgentTask` 和可重复触发的自动化任务 `Automation`。
- [x] 定时任务持久化。保存任务名称、Prompt、绑定会话、调度表达式、时区、启停状态。
- [x] 调度执行。支持 cron、固定间隔、指定时间、立即执行，默认使用 Asia/Shanghai。
- [x] 执行历史。已记录每次触发生成的 taskId、状态、开始/结束时间、错误、耗时、Token 调用/总量和工具调用/失败数。
- [x] 失败策略。支持全局 `maxRetryAttempts/retryBackoffSeconds/pauseAfterRetriesExhausted`，也支持单个自动化任务通过 metadata 覆盖；失败后按退避时间重排 `nextRunAt`，重试耗尽后可自动暂停。
- [x] 自动化管理台页面。支持创建、编辑、启用、暂停、立即执行、查看运行历史，并能配置单任务失败重试次数、退避秒数和重试耗尽后暂停。

## M4：安全、通道与审批

- [x] Web 内容提取与缓存。`web-fetch/web-search` 已接入正文提取和 Content Artifact 缓存，避免默认把完整 HTML/JS/CSS 送入模型。
- [~] 任务取消。当前已支持协作式 cancel，长阻塞工具/模型调用需要等待当前调用返回；后续由 worker 做强隔离终止。
- [x] Shell/cmd/PowerShell 工具。第一版已复用 `builtin.execute.command`：按 `command + args` 动态分类风险，`git status/diff/log/show`、目录/文本查看、版本查询等默认 low risk；delete/remove/overwrite/format、脚本执行、依赖安装、Git 写入类操作为 high risk，必须审批。工具会把 `npm install lodash`、`cmd \c ...` 这类整串命令归一成可执行文件和参数，避免 `CreateProcess error=2`。`ALLOWED_ROOTS` 表示允许访问范围，`DEFAULT_CWD` 表示未传 cwd 时的默认工作目录。同步 execute 不做跨平台命令黑名单猜测，只通过超时、退出码和输出记录约束前台命令。
- [~] 后台进程工具。已新增 `builtin.process.start/status/logs/stop`，作为 execute 能力域的一部分注册并复用 `ALLOWED_ROOTS`、`DEFAULT_CWD`、`MAX_OUTPUT_CHARS`；仅新增 `PROCESS_WAIT_MS` 控制启动确认窗口。`start` 用 `ProcessBuilder` 启动后台进程，短暂等待后确认进程是否仍存活，返回 pid、cwd、日志路径、日志尾部和可选健康检查 URL；`status` 查看托管进程状态；`logs` 查看日志尾部；`stop` 停止托管进程。进程表已持久化到 `.clawagent/processes/managed-processes.tsv`，重启后能恢复 pid、命令、cwd、日志、任务来源和健康检查 URL，并已有单测覆盖新增字段和旧 TSV 兼容；Linux/macOS 进程树实测暂缓到有对应环境时处理。
- [x] 工具调用结果查看。管理台聊天工具调用行已支持展开查看命令和结果；Runtime 同时记录受控完整 `output` 与 4000 字符 `outputPreview`，前端展开工具块时按 stepId 补拉完整事件并优先展示原始输出。
- [x] Planner 参数序列化修复。ReAct/LLM Planner 遇到数组或对象参数时保留 JSON 字符串，避免 `args=["status","--short"]` 被 `JsonNode.asText()` 读成空串导致 execute 执行裸命令。
- [x] 文件 diff 工作流。`builtin.filesystem.write_file` 已在写入前生成 `.clawagent/backups/filesystem` 备份，并在工具输出中记录 `changeType/path/backupPath/diff`；新增 `builtin.filesystem.rollback_file` 支持用备份恢复目标文件。filesystem 相对路径从 `DEFAULT_CWD` 解析，避免默认写到仓库根目录；任务级变更列表已接入，后端审查列表已按规范化路径折叠同一文件的旧版本，diff 可视化、历史恢复、Todo 关联和局部 hunk 回滚已完成，多 hunk 当前按逐块回滚处理。
- [x] 开发任务闭环。当前通过 execute 工具执行 `git status/diff`、测试命令并把 stdout/stderr、exitCode、riskLevel 写入步骤和事件；Runtime 已有重复失败阻断/恢复提示；新增开发任务摘要聚合 API 和管理台摘要页。验证计划已读取全局/项目级测试命令配置，并在管理台提供复制为 Agent 执行请求的入口，避免从摘要页直接绕过审批执行命令；失败分析也可复制为“继续修复”请求，便于用户把失败项带回聊天主流程。更主动的自动重试策略后续按真实任务反馈增强，不作为本地行动闭环阻塞项。
- [x] 权限和审批。默认 Guard 已按本次调用动态风险判断，高危工具要求 `approvedToolIds`、`allowHighRiskTools` 或 `toolPermissionMode=auto/full`；管理台支持“请求批准 / 替我审批 / 完全访问权限 / 自定义”四种模式。“请求批准”模式不预先展示高危工具白名单，高危工具被拦截后 Runtime 将任务状态置为 `WAITING_APPROVAL` 并阻塞当前任务线程，前端批准或拒绝后通过审批 API 唤醒同一个 task/step；批准继续执行，拒绝记录 `tool.approval_rejected` 并停止本次任务；“替我审批”只自动批准明确 high risk 的工具，medium/unknown 等程序无法确认的风险会退回人工确认；“完全访问权限”跳过工具审批确认；“自定义”模式使用 `local.approved-tool-ids` 保存的工具白名单，并随任务 metadata 记录。
- [ ] `claw-agent-worker` 隔离执行高危工具。当前尚无独立 worker 模块；先不为高危工具重复造第二套执行链路。
- [x] Prompt Injection Defense 最小版。Runtime 会检测工具输入/输出中的忽略指令、泄露系统提示词/密钥、绕过审批、隐藏审计等可疑内容，记录审计事件，并把后续非 low 工具转入人工确认。
- [~] ApprovalPolicy。已新增核心 `ApprovalPolicy` 领域对象，并让默认 `ToolExecutionGuard` 通过该对象解析 `toolPermissionMode/approvalMode/approvedToolIds/allowHighRiskTools`；单测覆盖 ask、auto、full、full-access、显式工具批准、JSON 数组批准、旧 metadata 兼容、提示词注入阻断和只读子 Agent 隔离。运行配置快照已返回 `policy.approval` 解释视图，管理台能力权限面板会展示当前生效审批模式和有效规则。2026-06-17 已新增 `/api/v1/config/policy` 专用保存入口，前端能力权限页已改走该接口，避免只改审批策略时误写模型配置；保存策略会记录 `policy.config_updated` 全局审计事件，便于后续在全局审计页追溯策略变更；`policy.resolutionOrder` 已提供 local/channel/task/agent-isolation/tool-enforcement 只读解析顺序说明。Channel 入站任务和只读子 Agent 已把策略来源、作用域和解析顺序写入 task metadata，方便跨任务审计检索。`ApprovalPolicyResolution` 已统一解析最终策略、来源、作用域、覆盖原因和冲突提示，管理台会展示作用域、策略覆盖原因和冲突提示。能力权限页已支持字段级维护审批模式和高危白名单，后续缺 Channel/User/Agent 维度完整合并规则。
- [~] PermissionPolicy。已新增核心 `PermissionPolicy` 领域对象，沉淀 allowed roots、敏感路径和 default cwd 的策略表达；运行配置快照已返回 `policy.permission` 解释视图，execute/filesystem 仍复用现有校验链路执行最终拦截；`/api/v1/config/policy` 保存后会同步 execute/filesystem 的本地工具环境，并随 `policy.config_updated` 审计事件记录安全摘要；管理台已展示策略解析顺序和底层工具强制校验层。能力权限页已补 workspace、默认 shell、allowed roots 和敏感路径规则的字段级编辑，后续缺 Channel/User/Agent 维度的策略合并、冲突解释和策略审计视图。
- [ ] RateLimitPolicy。

### 低优先级治理项

- [x] Channel 领域模型。`ChannelDefinition`、`ChannelInboundMessage`、`ChannelInboundResult` 已下沉到 `claw-agent-core`，不再定义在 server。
- [x] Channel SPI。`ChannelRegistry` 和 `ChannelAdapter` 已放入 `claw-agent-spi`，后续 IM 接入不需要依赖 server。
- [x] 通用 Channel 模块。`claw-agent-channel` 已提供 `FileChannelRegistry`、`ChannelRouter`、`ChannelSessionMapper`、`ChannelRuntimeAdapter`、`ChannelAdapterRegistry`、`ChannelInboundPayloadAdapter`、`ChannelOutboundClient` 和 `ChannelStreamClientManager`，支持 `webui/api/feishu/dingtalk` 模板、启停、入站/出站开关、审批模式和 approvedToolIds；飞书/钉钉/DDIO 内置 adapter 与外部 `ServiceLoader` adapter 进入同一注册表，门面不再按平台写死分发。`clawagent.channels.adapter-path` 已支持外部 adapter jar 或目录扫描，jar 内通过 `META-INF/services/com.github.clawagent.channel.ChannelRuntimeAdapter` 暴露实现类。飞书/钉钉平台实现已拆入 `channel.feishu.FeishuInboundAdapter/FeishuChannelAdapter/FeishuOutboundClient/FeishuStreamClient` 和 `channel.dingtalk.DingtalkInboundAdapter/DingtalkChannelAdapter/DingtalkOutboundClient/DingtalkStreamClient`。
- [~] Channel 配置和 WebUI 管理。后端已提供 `/api/v1/channels`、统一入站 API、`/api/v1/channels/{channelId}/health`、`/api/v1/channels/{channelId}/outbound/test` 和 `/api/v1/channels/{channelId}/stream/*`；管理台已新增 Channel 页面，支持查看、编辑、删除覆盖配置、维护审批模式/approvedToolIds/metadata、提交通用入站测试、检测连通性、手动出站测试，并可对飞书长连接/钉钉 Stream 做启动、停止和状态查看。Channel 创建/更新/删除已写入全局审计事件，审计只记录配置摘要；ChannelRouter 会用 Channel 配置覆盖入站消息自带的审批 metadata，并记录 `policy.approval.source/scope/resolutionOrder`，作为飞书、钉钉 adapter 共用的策略入口。管理台已补飞书/钉钉专用配置字段，可维护 Verification Token Env、Encrypt Key Env、App 凭证 Env、Tenant Token Env、Webhook URL Env、加签 Secret Env、Stream Client ID/Secret Env、连接模式、飞书出站 text/post、钉钉出站 text/markdown 和 markdown 标题；仍缺真实开放平台账号联调和更细的权限矩阵。
- [~] 飞书 Channel adapter：已作为 `claw-agent-channel` 内置官方 adapter 落地，支持明文 URL challenge、Verification Token、Encrypt Key 解密、文本事件入站、图片/文件/卡片/富文本占位文本与 metadata、`tenant_access_token` 缓存、`im/v1/messages` 文本回写、基础 post 回写和 SDK 长连接 `P2MessageReceiveV1` 文本消息入站；仍缺事件类型全量映射、媒体文件下载、卡片富渲染和真实开放平台联调。
- [~] 钉钉 Channel adapter：已作为 `claw-agent-channel` 内置 adapter 落地，支持自定义机器人文本入站解析、配置 Secret 后的 timestamp/sign 入站校验、图片/文件/Markdown/卡片占位文本与 metadata、webhook HMAC-SHA256 加签、文本/markdown 回写和 Stream SDK 通用事件入站；Stream 事件会优先抽取 `text.content/conversationId/senderStaffId/msgtype` 等常见字段。仍缺企业内部机器人事件全量映射、媒体文件下载、卡片富渲染和真实开放平台联调。
- [ ] Auth 本地用户。
- [~] API Token。已新增本地主服务 API Token 管理骨架：`/api/v1/auth/tokens` 支持列表、创建和撤销；管理台 Auth 页可生成 token，明文只展示一次，落盘保存 SHA-256 哈希和前缀，并展示强鉴权开关状态、token `lastUsedAt`、使用次数和最后访问入口；`clawagent.auth.api-token-required` 可开启 `/api/v1/**` 鉴权拦截，支持 `Authorization: Bearer` 和 `X-ClawAgent-Token`，鉴权成功会更新最小使用审计字段。Token 创建/撤销已写入全局审计事件，不记录 token 明文或 hash。后续缺 Token 权限绑定、独立审计查询页和本地用户。
- [~] Device 登记。已新增 `/api/v1/auth/devices`，支持设备列表、登记、心跳和撤销；管理台 Device 页已接入；设备登记、心跳和撤销已写入全局审计事件，不记录完整 metadata。仍缺配对码校验、设备密钥、用户绑定和设备级权限策略。
- [~] ToolGuard。已接入基础 `ToolExecutionGuard`，通道级策略和审批流待完成。

## M5：管理台与观测

- [~] 正式管理台。React + Vite 工程已落地，已提供总览、会话、工具、MCP、Skill、Token/成本、自动化、知识库、记忆、本地配置、内置能力、任务审计、文件审查、进程管理和全局审计入口；后续继续补全企业治理页面。
- [x] Setup Wizard。首次进入管理台会根据本地行动能力配置和健康检查状态弹出初始化向导；本地配置页也复用同一套部署向导。
- [~] Agent 配置页面。本地配置页已覆盖模型、workspace、allowed roots、默认 shell、权限模式、测试命令、最近项目、忽略规则、敏感路径、MCP/Skill 健康状态；完整 Agent/Channel/User 级配置后续再做。
- [~] Tool/Skill/MCP 管理页面。已具备系统工具只读、系统工具审批配置、MCP 导入/连接/刷新/删除/JSON 编辑保存、Skill 导入/启停/刷新/删除/Manifest 编辑保存和详情弹窗；Skill Manifest 编辑已改走显式 `PUT /api/v1/skills/{skillId}`，不会再把编辑动作混成安装动作；MCP `autoApprove` 已可在详情弹窗中按行显式维护，也保留 JSON 编辑能力；系统工具审批白名单可在“技能 -> 系统工具”或“配置 -> 内置能力”维护。系统工具行和详情弹窗已支持直接加入/移出审批白名单，技能页保存审批配置会调用 `/api/v1/config/policy`，不再误用模型配置保存入口。Skill 安装/导入/更新/删除/启用/停用和 MCP 注册/导入/更新/删除/连接/断开已写入全局审计事件。能力权限页已补本地权限字段级编辑；Channel/User/Agent 维度审批矩阵仍待完善。
- [x] Automation 管理页面。对应 M3A 的定时任务与自动化能力，已接入 React + Vite 正式管理台。
- [x] Memory 管理页面。对应 M2-P1 的短期和长期记忆设计。
- [x] 内置能力管理页面。本地配置页新增“内置能力” Tab，按 `agent`、`todo`、`read`、`search`、`edit`、`execute`、`web`、`browser`、`vscode` 能力域归类当前已注册系统工具；未注册能力作为桌面化/浏览器/IDE 后续接入预留，页面展示工具数量、能力启用状态、最高风险等级和未归类工具。
- [x] 内置能力权限配置。能力页复用 `local.permission-mode` 和 `local.approved-tool-ids`，展示每类能力描述、默认参数、审计策略、工具风险和当前权限结论；custom 模式可直接勾选高危工具白名单，并可在同一页维护 workspace、默认 shell、allowed roots 和敏感路径规则，统一通过 `/api/v1/config/policy` 保存到本地覆盖 YAML。Channel/user/agent 级策略仍保留到企业治理阶段。
- [~] Channel/Auth/Device 页面。Channel API 骨架和 Channel 管理页基础能力已完成；Auth 页已提供 API Token 生命周期管理；Device 页已提供本地设备登记、心跳和撤销；三类管理动作均已接入全局审计事件。仍缺本地用户页、设备配对码、设备级权限绑定和企业权限矩阵。
- [x] Task/Step 轨迹页面。任务详情、步骤、工具调用、文件变更、开发摘要和审计页签已能回放单任务轨迹；管理台会话页已接入跨任务/跨步骤检索，任务检索已覆盖 metadata，步骤检索已支持 `toolId/riskLevel` 过滤并展示风险列，筛选条件会保存在浏览器本地并支持一键重置，结果表会围绕关键词裁剪预览片段并高亮命中内容，并在结果上方显示任务/步骤聚合摘要；支持点击任务或步骤结果打开对应任务详情。更复杂的历史趋势和跨任务关联分析后续归入观测增强，不再阻塞当前闭环。
- [x] Audit Log 页面。已支持单任务审计查询 `/api/v1/tasks/{taskId}/audit`、全局审计事件查询 `/api/v1/audit/events` 和管理台“审计”页；系统日志仍走 `/api/v1/logs/query`，不和 AgentEvent 混用。
- [ ] Micrometer metrics。
- [ ] OpenTelemetry tracing。
- [x] Token/cost tracking。LLM 单次调用日志已记录 usage，会话/任务级 Token 聚合 API 已完成；管理台“配置 -> 成本规则”支持按模型维护输入/输出每百万 Token 单价和币种，Token 页会按当前规则估算会话、任务和模型维度成本。更细趋势报表后续归入观测增强，不再阻塞本地行动 Agent。
- [x] 会话上下文管理 API 与斜杠命令 UI。新增 `POST /api/v1/sessions/{sessionId}/context/clear`、`POST /api/v1/sessions/{sessionId}/context/compact`、`GET /api/v1/sessions/{sessionId}/context`、`GET /api/v1/sessions/{sessionId}/runtime-status`；运行时会按 `contextStartAt` 对应的上下文开始时间过滤旧消息，并在 compact 模式下注入会话摘要。前端已接入 `/clear`、`/compact`、`/context`、`/status`、`/resume`、`/workspace`、`/approval`、`/mcp`、`/tools`、`/todo`、`/diff`、`/logs`。

## M6：企业持久化和分布式

- [ ] MySQL 持久化实现。
- [ ] PostgreSQL 持久化实现。
- [ ] Redis 事件同步。
- [ ] Redis 分布式锁。
- [ ] 多节点任务抢占。
- [x] checkpoint/resume。运行时已记录恢复点并在 CONTINUATION_REQUIRED 场景下注入到 Planner 上下文；新增 `/api/v1/tasks/{taskId}/resume-state` 和聊天页继续执行入口，刷新历史会话后也能显示恢复 Todo、checkpoint 摘要、项目目录、恢复模式和剩余 Todo。继续执行时会把 checkpoint、剩余 Todo 和失败 Todo 重试策略一并提交给模型，并继承源任务项目目录/权限/知识库/附件上下文；运行中 `task.resume_requested` 和 `task.resume_checkpoint` 事件携带恢复模式、恢复策略和 Todo 状态，任务审计页会聚合为独立“恢复执行”区块。`todo.update_item` 通过 order 更新时已把 failed Todo 纳入可恢复计划，避免失败步骤无法重新置为 running/completed。多次 continuation 时，运行时恢复点选择已统一为优先 running、其次 failed、最后 pending，并把 resumeMode/resumeInstruction 写入 metadata，避免只剩失败 Todo 时恢复点丢失。
- [ ] 异步任务执行。
- [~] 多 Agent 编排。已提供父任务创建只读子 Agent 的最小 API，并把子任务创建事件写回父任务；管理台任务详情摘要会加载并展示子 Agent 列表，支持点击子任务继续查看详情，也可在任务摘要中创建新的只读子 Agent。2026-06-17 已补 `TaskStore.findSubTasks`，内存和 SQLite 存储都会按 `agent.parentTaskId` 显式查询父任务派生的子任务，不再依赖跨任务搜索兜底；2026-06-22 已补 `/api/v1/agents/{rootTaskId}/graph` 轻量编排图接口和管理台紧凑图展示，可查看父子任务计数、运行/等待/完成/失败状态和父子边关系；只读子任务 metadata 会记录 agent-isolation 策略来源、解析顺序和覆盖原因。仍缺并行调度、任务拆分策略、进程级隔离和独立 worker。
- [~] 子 Agent 上下文隔离。只读子任务会继承项目/知识库/附件上下文，但强制 `toolPermissionMode=ask`、清空批准列表，并由 `ToolExecutionGuard` 阻止非 low 风险工具；metadata 已记录 `policy.approval.source=agent-isolation:read-only`、`policy.approval.scope=agent`、解析顺序和覆盖原因；仍缺进程级隔离、资源限额和 Channel/User/Agent 策略合并。

## M7：向量记忆

- [x] SQLite 向量记忆能力落地。当前以 `memory_vector` 表 + `LocalMemoryProvider` 实现记忆条目的向量持久化和检索（含混合检索与 RRF 重排）。
- [x] JVector 本地 ANN 检索能力。`LocalMemoryProvider` 已采用 `jvector` 图索引构建本地近似向量检索。
- [x] MemoryItem 向量索引与 Markdown 同步导出。记忆条目入库时写入 `memory_vector`，并同步生成 `MEMORY.md` 与 `daily` 文件；当前不是监听 Markdown 文件反向生成向量索引。
- [ ] `VectorStore` SPI 与统一向量存储抽象。
- [ ] `QdrantVectorStore`。
- [ ] `MilvusVectorStore`。
- [ ] `PgVectorStore`。
- [ ] `OpenSearchVectorStore`。
- [ ] Task Summary 向量索引。

## 当前不做

- [ ] 不把文章中的不可解析 `harness-agent-spring-boot-starter` 作为依赖。
- [ ] 不默认启用高危 shell/file-write。
- [ ] 不默认启用向量库。
- [ ] 不把完整 session 原文写入 Markdown 记忆。
- [ ] 不在 core/spi 中强依赖 Spring AI。
