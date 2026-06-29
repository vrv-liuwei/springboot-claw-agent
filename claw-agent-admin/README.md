# ClawAgent Admin

管理后台 UI，用于配置模型、运行时、知识库、日志、健康检查等管理功能。它不是个人桌面版 App。

## 开发启动

先启动后端：

```powershell
$env:JAVA_HOME='D:\tools\Java\64\jdk17.0.7'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -pl claw-agent-server -am spring-boot:run "-Dspring-boot.run.arguments=--server.port=17891 --server.address=127.0.0.1"
```

启动 Admin 前端：

```powershell
cd claw-agent-admin
npm install
npm run dev
```

默认地址：

```text
http://127.0.0.1:5173
```

## 构建

```powershell
cd claw-agent-admin
npm run build
```

## 预览构建产物

```powershell
cd claw-agent-admin
npm run preview
```

默认预览地址：

```text
http://127.0.0.1:5174
```

## 和 App 的区别

- `claw-agent-admin`：管理后台，面向配置、运维、诊断。
- `claw-agent-app`：桌面/个人工作台，面向项目、对话、文件审查和本地能力。

两者复用同一个 `claw-agent-server` 后端，但 UI 入口和使用场景不同。
