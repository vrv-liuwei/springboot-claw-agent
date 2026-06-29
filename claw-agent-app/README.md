# ClawAgent App

本地桌面版 UI 和 Electron 启动器。UI 由 Spring Boot 托管在 `/app/`，Electron 只是桌面壳和系统能力层。

## 目录

- `ui/`：React + Vite 前端。
- `electron/`：Electron 主进程、预加载脚本和打包配置。
- `cli/`：Node.js 命令行入口，命令名为 `clawagent`。
- `shared/`：UI、Electron 和 CLI 共用的客户端配置、API 路径和请求客户端，例如服务器地址、`local/remote` 判断、`/api/v1` 路径。
- `scripts/`：运行时准备和调试辅助脚本。

## 开发启动

源码开发需要 Node.js 18+。发布版 CLI 通过 `ClawAgent.exe --cli` 运行，不要求 ToC 用户额外安装 Node.js。

先启动后端：

```powershell
$env:JAVA_HOME='<你的 JDK 17 安装目录>'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -pl claw-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--server.port=17891 --server.address=127.0.0.1 --clawagent.mode=local"
```

启动 App 前端开发服务器：

```powershell
cd claw-agent-app
npm install
npm run dev
```

浏览器访问：

```text
http://127.0.0.1:5180
```

后端托管访问：

```text
http://127.0.0.1:17891/app/
```

## Electron 本地启动

Electron 默认打开本地 Spring Boot 的 `/app/` 地址：

```powershell
cd claw-agent-app
npm run electron
```

如果要测试完整内置后端流程，先构建前端和 server：

```powershell
cd claw-agent-app
npm run build
npm run package-server
npm run electron
```

Electron 设置页支持切换服务器地址：

- local 默认：`http://127.0.0.1:17891`
- remote：在「设置 -> 连接」里填写远程 `claw-agent-server` 地址
- 「保存并切换」会检查企业服务是否可用；「仅保存不检查」用于离线预配置企业地址
- 从 remote 切回 local：在「设置 -> 连接」里点击「恢复本地默认地址」
- 如果已保存的远程服务启动时不可用，Electron 会临时启动本地服务来打开 UI 和设置页，但不会自动重置已保存的远程地址。

该配置保存在桌面客户端用户目录，CLI 会读取同一份配置。需要强制指定配置目录时，可同时给 Electron 和 CLI 设置 `CLAWAGENT_CONFIG_DIR`。

## API 路径统一管理

App 前端、Electron 主进程和 Node.js CLI 共用 `shared/api-paths.mjs` 管理 `/api/v1` 路径；CLI 和 Electron 共用 `shared/server-client.mjs` 做 JSON 请求和健康检查。

新增或调整 App 相关接口时，优先修改：

```text
shared/api-paths.mjs
shared/server-client.mjs
```

然后在 UI、Electron、CLI 中引用 `API_PATHS` 或 shared client，不要在多个入口里重复手写 `/api/v1/...` 字符串和基础 fetch 逻辑。

## CLI 命令行

开发阶段直接运行：

```powershell
cd claw-agent-app
npm run cli -- server status
npm run cli -- chat "你好"
npm run cli -- run --workspace D:\workspace\demo "分析这个项目"
```

如果用 `npm link` 注册本地命令：

```powershell
cd claw-agent-app
npm link
clawagent server status
clawagent chat "你好"
```

服务器地址配置：

```powershell
clawagent config server
clawagent config path
clawagent config server http://127.0.0.1:17891
clawagent config server http://company-server:17891
clawagent config server http://company-server:17891 --no-check
clawagent config server --reset
```

默认情况下，配置企业服务器地址会先检查服务是否可用。`--no-check` 只用于离线预配置地址。
`--reset` 会恢复为 local 默认地址 `http://127.0.0.1:17891`。
local CLI 会优先启动或连接 `127.0.0.1:17891`；如果该端口被其他进程占用，会临时使用后续空闲端口完成本次命令，不会修改已保存的默认地址。

常用命令：

```powershell
clawagent chat "普通对话"
clawagent run --workspace D:\workspace\demo "分析项目"
clawagent session list
clawagent logs tail --limit 80
clawagent server status
```

其他智能体调用时建议使用 JSON 输出：

```powershell
clawagent session list --json
clawagent logs tail --json
clawagent run --json "分析当前状态"
```

`chat --json` 和 `run --json` 会输出 JSONL 事件流，每行格式为 `{"event":"...","data":{...}}`。
带 `--json` 的命令失败时，stderr 会输出 `{"ok":false,"error":"..."}`，便于其他智能体或脚本解析失败原因。

## 打包

生成可安装包：

```powershell
cd claw-agent-app
npm run dist
```

`npm run dist` 会打包 UI、server jar、内置 Java runtime，并把 `cli/` 和 `shared/` 放入安装包资源中。

发布包内的 CLI 入口为：

```text
resources\cli\clawagent.cmd
```

安装器会把下面目录注册到当前用户 PATH：

```text
<ClawAgent 安装目录>\resources\cli
```

安装完成后，用户打开新终端可以直接执行：

```powershell
clawagent chat "你好"
```

`clawagent.cmd` 会转调桌面程序的 CLI 模式：

```text
ClawAgent.exe --cli ...
```

因此发布版不要求 ToC 用户额外安装 Node.js。发布版 CLI 会继承 Electron 的 `userData` 目录，默认与桌面 UI 读写同一份客户端配置和本地运行数据。

卸载 ClawAgent 时，安装器会从用户 PATH 中移除这个 CLI 目录。

只生成 unpacked 目录，适合快速验证：

```powershell
cd claw-agent-app
npm run dist:dir
```

## 内置 JRE

发布给普通用户时应内置 Java 17 runtime。`prepare-runtime` 会准备 Electron extraResources 使用的 server jar 和 runtime 文件。

如果本机已经有 JDK 17，开发阶段不需要每次重新生成 runtime。

## Electron 下载缓存

项目根目录已有本地缓存目录：

- `.electron-cache`
- `.electron-builder-cache`

`npm run dist` 和 `npm run dist:dir` 会通过 `scripts/run-dist.mjs` 自动设置绝对缓存路径，并在打包开始时打印：

```text
ELECTRON_CACHE=<项目根目录>\.electron-cache
ELECTRON_BUILDER_CACHE=<项目根目录>\.electron-builder-cache
```

如果打包时仍然重复下载 Electron，优先检查打印出的缓存路径和缓存文件名是否匹配当前 Electron 版本。

## 常用命令

```powershell
cd claw-agent-app
npm run build
npm run package-server
npm run refresh:unpacked
npm run dist:dir
```
