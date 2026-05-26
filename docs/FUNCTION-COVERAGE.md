# ClawAgent 与文章功能覆盖对照

来源：`E:/data/obsidian/Agent/Harness Agent：2026 年 Java AI Agent 开发的终极框架.md`

结论：`clawagent` 的规划目标可以覆盖文章描述的 Harness Agent 功能，并在企业治理、checkpoint、审批、安全边界、审计方面做增强。当前仓库已经完成 M1、M2 主线能力和 M3 的 MCP/Skill/toolkit 主链路，仍需继续补齐 M4-M7 的企业治理、正式管理台、分布式和向量记忆能力。

## 覆盖矩阵

| 文章能力 | 文章描述 | clawagent 规划 | 覆盖结论 |
| --- | --- | --- | --- |
| 原生 Java | 完全用 Java 编写，无 Python 依赖 | Java 17，多模块 Maven 工程 | 计划支持 |
| Spring Boot 集成 | 自动配置，开箱即用 | `claw-agent-spring-boot-starter` | 已支持 |
| Spring AI 集成 | 基于 Spring AI Model 接口 | `claw-agent-model-spring-ai` 提供可选 ChatClient 适配器 | 已支持可选接入 |
| 轻量核心 | 核心 jar 约 1MB | core 不依赖 Spring，runtime 分模块 | 目标支持，需实现后验证体积 |
| Agent | SimpleAgent、ReActAgent、PlanAndExecuteAgent、MultiAgent | Runtime + single/ReAct/tool-calling planner；MultiAgent 后续 | 已支持单 Agent 主链路，MultiAgent 后续 |
| Tool | `Tool` 接口，自定义工具，自动扫描 | `AgentTool`、`AgentToolRegistry`、starter 自动注册、ToolExecutionGuard、内置 filesystem/web-fetch/weather/time | 已支持基础能力，并增强治理 |
| Memory | InMemory、Redis、Database、VectorStore | 已有会话消息、会话摘要、Markdown 记忆目录和自动提升；Redis/JDBC/Vector 后续扩展 | 已支持单机结构化记忆，分布式和向量后续 |
| Planner | Simple、StepByStep、Hierarchical | rule-based、single JSON planner、ReAct、tool-calling；PlanAndExecute/Hierarchical 后续 | 已支持主要单 Agent Planner |
| Executor | 同步、异步、流式、并行 | M1 同步，M2 流式，M6 异步/并行 | 已支持同步和流式，异步/并行后续 |
| LLM | 支持豆包、通义、OpenAI、Claude 等 | OpenAI 兼容 `ModelClient` + Spring AI 可选适配 | 已支持可扩展接入 |
| Callback | 日志、监控、追踪、限流、安全扩展点 | `AgentCallback` + `CallbackPipeline` | 计划支持 |
| 执行流程 | 输入、加载历史、规划、执行、处理结果、判断完成、最终回答 | `AgentRuntime` 全链路建模 | 已支持主链路 |
| 天气/计算/数据库示例 | 示例工具 | `claw-agent-toolkit` 已内置天气/时间/WebFetch/Filesystem，计算器已移除 | 已支持常用示例工具 |
| 多 Agent 协作 | MultiAgent，协调器 | M5：MultiAgent、coordinator、sub-agent isolation | 计划支持，并增加隔离 |
| 流式输出 | `executeStream` | 任务事件 SSE + 模型 token/chunk stream | 已支持 |
| 错误处理与重试 | RetryPolicy、ErrorHandler | Runtime/MCP/Skill 已有基础错误记录，正式 RetryPolicy/ErrorHandler 后续 | 分阶段支持 |
| 监控与追踪 | Micrometer、OpenTelemetry | `claw-agent-observability` | 计划支持 |
| 限流 | 企业级限流能力 | `RateLimitPolicy` | 计划支持 |
| 安全 | 企业级安全能力 | 已接入基础 `ToolExecutionGuard`；审批、脱敏、PromptInjectionGuard 后续 | 分阶段支持，并增强 |
| 生产可用 | 文章声称适合生产 | M3-M6 才进入生产能力建设 | 不承诺现阶段支持 |

## clawagent 比文章方案额外强调的能力

| 增强能力 | 原因 |
| --- | --- |
| Checkpoint / Resume | 企业长任务不能只依赖一次 HTTP 请求内存执行 |
| AgentTask / AgentStep 持久化 | 每一步要可恢复、可审计、可回放 |
| Human Approval | 涉及生产变更、财务、客户数据等操作必须人工审批 |
| ToolGuard | 模型不能绕过权限直接调用工具 |
| 数据脱敏 | 上下文、日志、审计中不能泄露敏感数据 |
| 成本统计 | 生产环境需要 token 和模型成本治理 |
| 模型框架解耦 | Spring AI 是适配器，不是 Harness 内核 |
| 子 Agent 隔离 | 多 Agent 协作时需要独立上下文、权限和审计链路 |

## 风险判断

文章中的 `io.harness.agent:harness-agent-spring-boot-starter:0.10.0` 和 `org.springframework.ai:spring-ai-doubao-spring-boot-starter:1.0.0` 依赖坐标此前已验证无法从当前阿里云 Maven 镜像和 Maven Central 解析。因此 `clawagent` 不应依赖这些坐标作为核心路线。

更稳妥的路线是：

```text
自研 Harness Runtime
  + Spring AI 作为模型适配器
  + 自定义 Tool / Memory / Planner / Executor
  + 企业级 Security / Observability / Audit
```

## 当前阶段是否支持文章所有功能

不支持。

当前 M1-M3 已支持核心单机主链路：

- AgentTask
- AgentStep
- 同步 Executor 和 SSE 流式事件
- AgentToolRegistry
- SQLite 会话、消息、任务、步骤、事件持久化
- OpenAI 兼容模型和 Spring AI 可选适配
- single / ReAct / tool-calling planner
- MCP Server 配置、连接和 tool/resource/prompt 管理
- Skill 本地安装、启停、工具加载和多类执行器
- 内置 filesystem / web-fetch / weather / time 工具
- 基础审计事件和 ToolExecutionGuard

## 规划完成后是否支持文章所有功能

按当前路线，M1-M6 完成后可以覆盖文章列出的所有主要功能：

- Agent 类型
- 工具调用
- 记忆管理
- 规划执行
- 多 Agent 协作
- 流式输出
- 错误重试
- 监控追踪
- 限流安全
- Spring Boot 集成

并额外覆盖文章没有展开但企业落地必需的：

- checkpoint
- resume
- human approval
- tool permission
- structured task state
- audit replay
- cost tracking
- sub-agent isolation
