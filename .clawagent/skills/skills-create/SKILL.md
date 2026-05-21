---
name: skills-create
description: 创建或更新 ClawAgent Skill。用于用户要求创建技能、更新技能、生成 SKILL.md、设计 Skill manifest、整理 scripts/references/assets、校验技能可用性时。
---

# Skills Create

使用这个 Skill 创建或更新 ClawAgent 本地 Skill。

## ClawAgent Skill 结构

推荐目录：

```text
<skill-id>/
  manifest.json
  SKILL.md
  references/
  scripts/
  assets/
```

`manifest.json` 是 ClawAgent 运行时读取的安装声明：

```json
{
  "id": "my-skill",
  "name": "My Skill",
  "version": "0.1.0",
  "description": "什么时候使用这个 Skill",
  "enabled": true,
  "entrypoint": "SKILL.md",
  "tools": ["default"],
  "permissions": [],
  "metadata": {}
}
```

`SKILL.md` 使用 Codex 风格：

```markdown
---
name: my-skill
description: 清楚描述这个 Skill 做什么，以及什么时候触发。
---

# My Skill

执行步骤和关键约束。
```

## 创建流程

1. 先确认技能用途和触发场景。
2. 使用小写字母、数字和连字符命名，目录名等于 `id`。
3. 保持 `SKILL.md` 精简，只写 Agent 必须知道的流程。
4. 大段知识放入 `references/`，脚本放入 `scripts/`，模板和素材放入 `assets/`。
5. 不创建多余文档，例如 README、INSTALLATION_GUIDE、CHANGELOG。
6. 如果 Skill 需要稳定重复执行的逻辑，优先写脚本而不是让 Agent 每次重写。
7. 设置最小权限：
   - 只读文件：`file-read`
   - 写文件：`file-write`
   - 调 shell：`shell`
   - 网络访问：`network`

## 安装方式

通过 ClawAgent API 安装：

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
  content = Get-Content .\my-skill\SKILL.md -Raw
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Uri 'http://localhost:17890/api/v1/skills' -Method Post -Body $body -ContentType 'application/json; charset=utf-8'
```

## 校验清单

- `id` 使用 kebab-case。
- `description` 同时说明“做什么”和“什么时候用”。
- `entrypoint` 指向 Skill 目录内文件，不能越权到外部路径。
- `tools` 至少包含 `default`，除非这是纯说明型 Skill。
- `permissions` 不要过度声明。
- 内容足够短，复杂资料放 references。
