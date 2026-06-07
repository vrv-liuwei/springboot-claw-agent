# ClawAgent 后期计划

## 状态说明

- `[x]` 已完成并通过编译。
- `[~]` 已有骨架或占位实现。
- `[ ]` 未完成。

## 当前优先级顺序

1. [x] 短期记忆与长期记忆重新设计。已落地 global/channel/session 长期记忆、本轮上下文构建、候选提炼、命中记录和管理台维护。
2. [~] 正式管理台持续优化。后台新增的业务功能必须同步补充管理台页面，旧原生 Console 暂时保留。
3. [~] 智能体定时任务与自动化。单机版任务配置、启停、立即执行、基础执行历史和管理台页面已完成；失败重试和更细执行统计未完成。
4. [ ] Shell/cmd/PowerShell 工具。第一阶段先屏蔽复杂权限管控，查询类命令不审批，删除/覆盖/破坏性操作必须用户确认。
5. [ ] Skill 目录风格和加载逻辑调整。每个 Skill 必须独立目录保存，加载器按目录扫描并注册 manifest、入口文件、scripts、assets、references、lib。

## 近期执行拆解

1. 短期和长期记忆：已完成第一版实现；后续继续增强去重、冲突处理、过期策略和更细质量评分。
2. 正式管理台：后台每新增一个业务 API，同步增加管理台页面或入口；下一批优先补记忆、Shell 工具、审批/权限配置页面。
3. 智能体定时任务与自动化：先补失败重试、退避间隔、失败后暂停，再把运行历史补齐耗时、Token 和工具链路聚合。
4. Shell/cmd/PowerShell 工具：先实现查看类命令免审批执行，删除、覆盖、格式化等破坏性操作进入用户确认；worker 隔离放到后续增强。
5. Skill 目录和加载逻辑：调整为 `.clawagent/skills/<skillId>/` 独立目录，加载器按目录保留原始 Skill ID，并完整加载 `manifest.json`、`SKILL.md`、`scripts/`、`assets/`、`references/`、`lib/`。

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
- [x] `claw-agent-server` 独立 Spring Boot 服务，默认端口 `17890`。
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
- [~] 长期记忆质量治理。已具备入库、检索、候选审核、启停、删除和命中记录，去重、合并、冲突处理和过期策略仍需增强。
- [x] Token usage 记录。LLM 调用日志已有 token usage，并已提供会话/任务级聚合查询 API；成本规则仍放到 M5 观测增强。

### M2-P1：短期与长期记忆设计

- [x] 短期记忆设计。Runtime 每轮构建统一 `runtime.memoryContext`，包含会话摘要、最近消息、Todo 状态和命中的 active 长期记忆。
- [x] 长期记忆设计。长期 scope 只启用 `global/channel/session`；`task` 仅作为运行时来源，不作为长期 scope。
- [~] 记忆摘要策略。会话摘要和候选提炼已接入；长网页、仓库文件、工具输出的统一摘要质量后续继续增强。
- [~] 记忆质量治理。已支持 pending/active/disabled/archived、启停、删除和命中记录；去重、合并、冲突处理、质量评分和过期策略后续增强。
- [x] 记忆管理台页面。支持列表、搜索、新增、编辑、启用、禁用、删除、候选处理和命中查看。

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
- [ ] Skill 目录规范调整。每个 Skill 固定保存为 `.clawagent/skills/<skillId>/`，目录内包含 `manifest.json`、`SKILL.md`、`scripts/`、`assets/`、`references/`、`lib/` 等资源，不再把外部 Skill 折叠成通用 `imported-skill`。
- [ ] Skill 加载逻辑调整。启动和刷新时按 Skill 子目录扫描，保留原始 Skill ID，完整加载脚本、资源、引用和 Java 插件 Jar。
- [x] 工具执行前置拦截点。Runtime 已接入 `ToolExecutionGuard`，默认高危工具需要 metadata 显式审批。
- [x] 内置 filesystem 工具。已支持读取文本、列目录、搜索文件、文件信息、受控写入；配置统一走 `clawagent.toolkit.tools.filesystem.env`，不依赖外部 MCP filesystem。
- [x] M3 管理台入口。新增 React + Vite 管理台源码模块 `claw-agent-admin`，构建后访问 `/admin/index.html`；保留原 `/console/index.html` 用于聊天调试。
- [x] MCP `autoApprove`。配置已接入工具风险等级：命中规则的 MCP tool 为 low risk，未命中默认 high risk。
- [~] MCP 连接健壮性。stdio、HTTP、SSE 主链路可用，已有 timeout、启动隔离和运行态按需重连；更复杂的断线检测和会话关闭后续增强。

## M3A：智能体定时任务与自动化

- [x] Automation 领域模型。区分一次性执行记录 `AgentTask` 和可重复触发的自动化任务 `Automation`。
- [x] 定时任务持久化。保存任务名称、Prompt、绑定会话、调度表达式、时区、启停状态。
- [x] 调度执行。支持 cron、固定间隔、指定时间、立即执行，默认使用 Asia/Shanghai。
- [~] 执行历史。已记录每次触发生成的 taskId、状态、开始/结束时间和错误；耗时、Token 和工具链路聚合仍需增强。
- [ ] 失败策略。支持失败重试、最大重试次数、退避间隔和失败后暂停。
- [x] 自动化管理台页面。支持创建、编辑、启用、暂停、立即执行和查看运行历史。

## M4：安全、通道与审批

- [x] Web 内容提取与缓存。`web-fetch/web-search` 已接入正文提取和 Content Artifact 缓存，避免默认把完整 HTML/JS/CSS 送入模型。
- [~] 任务取消。当前已支持协作式 cancel，长阻塞工具/模型调用需要等待当前调用返回；后续由 worker 做强隔离终止。
- [ ] Shell/cmd/PowerShell 工具。第一版只做本机命令执行基础能力；查询类命令不审批，delete/remove/overwrite/format 等破坏性操作必须用户确认。
- [ ] `claw-agent-worker` 隔离执行高危工具。
- [ ] Prompt Injection Defense。
- [ ] ApprovalPolicy。
- [ ] PermissionPolicy。
- [ ] RateLimitPolicy。

### 低优先级治理项

- [ ] Channel 领域模型。
- [ ] Channel 配置和 WebUI 管理。
- [ ] Auth 本地用户。
- [ ] API Token。
- [ ] 设备配对。
- [~] ToolGuard。已接入基础 `ToolExecutionGuard`，通道级策略和审批流待完成。

## M5：管理台与观测

- [~] 正式管理台。React + Vite 工程已落地，已提供总览、会话、工具、MCP、Skill、Token、自动化、知识库、记忆等页面；审批、配置、Shell 工具等业务能力后续同步补齐。
- [ ] Setup Wizard。
- [ ] Agent 配置页面。
- [~] Tool/Skill/MCP 管理页面。已具备基础列表和状态视图，编辑、测试连接、启停和审批配置待完善。
- [x] Automation 管理页面。对应 M3A 的定时任务与自动化能力，已接入 React + Vite 正式管理台。
- [x] Memory 管理页面。对应 M2-P1 的短期和长期记忆设计。
- [ ] 内置能力管理页面。参考能力树分组展示和开关管理：`agent` 任务智能体、`browser` 浏览器页面交互、`edit` 工作区文件编辑、`execute` 本机代码/程序执行、`read` 工作区文件读取、`search` 工作区文件搜索、`todo` 待办/任务规划、`vscode` VS Code 能力、`web` Web 信息提取。
- [ ] 内置能力权限配置。支持查看每类能力的描述、风险等级、启用状态、允许的 channel/user/agent、默认参数和审计策略。
- [ ] Channel/Auth/Device 页面。
- [ ] Task/Step 轨迹页面。
- [ ] Audit Log 页面。
- [ ] Micrometer metrics。
- [ ] OpenTelemetry tracing。
- [~] Token/cost tracking。LLM 单次调用日志已记录 usage，会话/任务级 Token 聚合 API 已完成；成本规则和更细页面查询未完成。

## M6：企业持久化和分布式

- [ ] MySQL 持久化实现。
- [ ] PostgreSQL 持久化实现。
- [ ] Redis 事件同步。
- [ ] Redis 分布式锁。
- [ ] 多节点任务抢占。
- [ ] checkpoint/resume。
- [ ] 异步任务执行。
- [ ] 多 Agent 编排。
- [ ] 子 Agent 上下文隔离。

## M7：向量记忆

- [ ] `VectorStore` SPI。
- [ ] `InMemoryVectorStore`。
- [ ] `SqliteVectorStore` 调研和实现。
- [ ] `QdrantVectorStore`。
- [ ] `MilvusVectorStore`。
- [ ] `PgVectorStore`。
- [ ] `OpenSearchVectorStore`。
- [ ] Markdown Memory 向量索引。
- [ ] Task Summary 向量索引。

## 当前不做

- [ ] 不把文章中的不可解析 `harness-agent-spring-boot-starter` 作为依赖。
- [ ] 不默认启用高危 shell/file-write。
- [ ] 不默认启用向量库。
- [ ] 不把完整 session 原文写入 Markdown 记忆。
- [ ] 不在 core/spi 中强依赖 Spring AI。
