# ClawAgent 后期计划

## 状态说明

- `[x]` 已完成并通过编译。
- `[~]` 已有骨架或占位实现。
- `[ ]` 未完成。

## M1：可启动平台

- [x] Maven 多模块工程。
- [x] Java 17 编译配置。
- [x] `claw-agent-core` 核心领域对象。
- [x] `claw-agent-spi` 扩展接口。
- [x] `claw-agent-runtime` 同步执行链路。
- [x] `claw-agent-toolkit` 工具注册和内置工具。
- [x] `claw-agent-persistence-sqlite` SQLite3 任务/步骤持久化。
- [x] `claw-agent-memory-markdown` Markdown 记忆目录初始化和搜索。
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
- [x] Markdown 长期记忆自动提升策略。
- [~] Markdown 长期记忆质量治理。已具备目录初始化、检索和摘要提升，去重、合并、质量评分仍需增强。
- [~] Token usage 记录。LLM 调用日志已有 token usage，后续需要补充聚合统计和查询 API。

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
- [x] 工具执行前置拦截点。Runtime 已接入 `ToolExecutionGuard`，默认高危工具需要 metadata 显式审批。
- [x] 内置 filesystem 工具。已支持读取文本、列目录、搜索文件、文件信息、受控写入；配置统一走 `clawagent.toolkit.tools.filesystem.env`，不依赖外部 MCP filesystem。
- [~] MCP `autoApprove`。配置已保存，正式审批策略和通道级策略将在 M4 接入。
- [~] MCP 连接健壮性。stdio、HTTP、SSE 主链路可用，断线重连、超时恢复、会话关闭仍需增强。

## M4：安全、通道与审批

- [ ] 在获取网页内容后，会将整个网页js,css,html都发给模型，有点浪费token了。比如：这个网页写的是什么：https://github.com/mrkrsl/web-search-mcp
- [ ] Channel 领域模型。
- [ ] Channel 配置和 WebUI 管理。
- [ ] Auth 本地用户。
- [ ] API Token。
- [ ] 设备配对。
- [~] ToolGuard。已接入基础 `ToolExecutionGuard`，通道级策略和审批流待完成。
- [ ] PermissionPolicy。
- [ ] RateLimitPolicy。
- [ ] ApprovalPolicy。
- [ ] Prompt Injection Defense。
- [ ] Shell/cmd/PowerShell 工具。
- [ ] `claw-agent-worker` 隔离执行高危工具。

## M5：管理台与观测

- [ ] React + Vite 正式 WebUI。
- [ ] Setup Wizard。
- [ ] Agent 配置页面。
- [ ] Tool/Skill/MCP 管理页面。
- [ ] 内置能力管理页面。参考能力树分组展示和开关管理：`agent` 任务智能体、`browser` 浏览器页面交互、`edit` 工作区文件编辑、`execute` 本机代码/程序执行、`read` 工作区文件读取、`search` 工作区文件搜索、`todo` 待办/任务规划、`vscode` VS Code 能力、`web` Web 信息提取。
- [ ] 内置能力权限配置。支持查看每类能力的描述、风险等级、启用状态、允许的 channel/user/agent、默认参数和审计策略。
- [ ] Channel/Auth/Device 页面。
- [ ] Task/Step 轨迹页面。
- [ ] Audit Log 页面。
- [ ] Micrometer metrics。
- [ ] OpenTelemetry tracing。
- [~] Token/cost tracking。LLM 单次调用日志已记录 usage，聚合统计、成本规则和页面查询未完成。

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
