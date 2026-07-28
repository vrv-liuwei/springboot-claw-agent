`claw-agent-worker` 的设计理由不是为了替代现有 `execute/process`，而是补它们在“高危工具执行”上的硬隔离能力。

现有功能能满足：

- 普通命令执行：`git status`、`rg`、`mvn test`、`npm run build`
- 风险分类、审批、allowed roots、敏感路径拦截
- 命令输出记录、超时、前端展示
- 后台进程启动、查看、停止

但现有功能不够的是：

1. **执行进程和主服务在同一个 JVM 控制链路里**
   `execute` 虽然启动的是外部命令，但调度、等待、输出读取都在主服务线程里。命令异常卡死、输出流异常、子进程树失控时，主服务更容易被拖住。

2. **高危命令需要更强的“可杀死边界”**
   比如安装依赖、执行脚本、启动未知命令、生成大量子进程。审批只能决定“能不能执行”，不能保证“出问题一定能快速切断”。

3. **后续桌面 Agent / 多 Agent 隔离需要底座**
   桌面 Agent 会执行更多本地动作。以后如果有多个 Agent 并发执行，不能都直接压在主 server 里跑。worker 是后续做：
   - 独立进程隔离
   - 强终止
   - 资源限制
   - worker 池
   - 子 Agent 隔离  
   的基础。

4. **企业/本地生产力场景需要审计和隔离分层**
   策略层负责“准不准执行”，worker 负责“在哪里执行、怎么限制、怎么杀掉”。这两个职责不应该混在 `ExecuteCommandTool` 里。

所以结论是：

- **如果只是本地单用户、低风险命令，现有功能已经够用。**
- **如果目标是可靠的本地行动 Agent，尤其要执行高危工具，现有功能还差进程级隔离。**
- `claw-agent-worker` 应该是一个“只在高危命令时启用”的隔离层，不应该把所有命令都绕进去，也不应该做成第二套复杂执行系统。

补充：`claw-agent-worker` 现在还提供 `ClawAgentSubAgentWorkerMain` 作为子 Agent external-process 协议适配入口。它只负责读取 server 下发的任务 JSON、转发给 `--` 后面的下游 Runtime 命令，并包装或透传 `CLAW_SUBAGENT_WORKER_RESULT_V1` 结果；它不内置模型 Runtime，也不改变“不要在 worker 模块里做第二套完整执行系统”的边界。

我现在做的方向就是这个：低/中风险继续走现有 `execute`，只有 high risk 且 `WORKER_ENABLED=true` 时才走 worker。这样不会推翻现有能力。
