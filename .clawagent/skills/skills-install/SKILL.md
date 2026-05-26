---
name: skills-install
description: 安装、列出、启用、禁用 ClawAgent Skill。用于用户要求导入本地 Skill、安装 Codex/Claude Skill、从 GitHub 仓库安装 Skill、从 SKILL.md 转换安装、查看已安装 Skill、启用禁用 Skill 或管理 Skill 状态时。
---

# Skills Install

使用这个 Skill 管理 ClawAgent 本地 Skill。

## 本地保存位置

默认目录：

```text
.clawagent/skills/<skill-id>/
  manifest.json
  SKILL.md
```

已启用的 Skill 会自动注册为工具：

```text
skill.<skillId>
skill.<skillId>.<toolName>
```

## 常用 API

查询已安装 Skill：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills'
```

安装 Skill：

```powershell
$body = @{
  manifest = @{
    id = 'my-skill'
    name = 'My Skill'
    version = '0.1.0'
    description = '什么时候使用这个 Skill'
    enabled = $true
    entrypoint = 'SKILL.md'
    tools = @('default')
    permissions = @()
    metadata = @{}
  }
  content = '# My Skill'
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills' -Method Post -Body $body -ContentType 'application/json; charset=utf-8'
```

安装 Codex/Claude 风格 Skill：

- 如果用户给 `SKILL.md` 原文，调用 `skill.skills-install.install`，参数 `skillMd` 填 SKILL.md 内容。
- 如果用户给 GitHub 仓库 URL，必须直接调用 `skill.skills-install.install`，参数 `sourceUrl` 填仓库 URL；不要先用 `web.fetch` 读取 SKILL.md 再传 `skillMd`，否则 `scripts/`、`references/`、`assets/` 等仓库资源无法自动下载。
- 如果用户要求覆盖同名 Skill，传 `overwrite=true`。
- 可选参数：`id`、`name`、`description`，用于覆盖 frontmatter 自动解析结果。

工具参数示例：

```json
{
  "sourceUrl": "https://github.com/owner/repo.git",
  "overwrite": false
}
```

启用或禁用：

```powershell
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills/my-skill/enable' -Method Post
Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills/my-skill/disable' -Method Post
```

## 安装原则

1. 不覆盖用户已有同名 Skill，除非用户明确要求。
2. 安装前检查 `manifest.id`、`entrypoint`、`tools`、`permissions`。
3. 不把密钥写进 `manifest.json` 或 `SKILL.md`。
4. 外部仓库安装前先说明来源和落盘目录。
5. 安装后刷新 `/api/v1/tools`，确认工具已出现。
6. 如果服务未运行，只能写入目录；服务启动后会自动加载。

## 与 Codex Skill 的差异

ClawAgent 当前读取 `manifest.json` 作为运行时声明，`SKILL.md` 是 Agent 使用说明。Codex 只依赖 frontmatter 触发，而 ClawAgent 还会根据 `tools` 和 `permissions` 注册工具与风险等级。
