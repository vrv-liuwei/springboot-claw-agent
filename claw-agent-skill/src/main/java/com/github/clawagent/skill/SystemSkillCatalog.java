package com.github.clawagent.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClawAgent 内置系统 Skill。
 * 这些 Skill 会在本地目录缺失时自动落盘，用户可以查看、禁用或覆盖自己的版本。
 */
final class SystemSkillCatalog {
    private SystemSkillCatalog() {
    }

    static List<SkillPackage> packages() {
        return List.of(skillCreate(), skillInstall());
    }

    private static SkillPackage skillCreate() {
        SkillManifest manifest = new SkillManifest(
                "skills-create",
                "Skills Create",
                "0.1.0",
                "创建或更新 ClawAgent Skill。用于生成 Skill manifest、SKILL.md、references/scripts/assets 目录，并校验 Skill 是否适合被 Agent 使用。",
                true,
                "SKILL.md",
                List.of("default", "create"),
                List.of("file-read", "file-write"),
                Map.of("system", true, "source", "clawagent"));
        return new SkillPackage(manifest, """
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
                  content = Get-Content .\\my-skill\\SKILL.md -Raw
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
                """);
    }

    private static SkillPackage skillInstall() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("system", true);
        metadata.put("source", "clawagent");
        SkillManifest manifest = new SkillManifest(
                "skills-install",
                "Skills Install",
                "0.1.0",
                "安装、列出、启用、禁用 ClawAgent Skill。用于用户要求导入本地 Skill、从 JSON 安装 Skill、查看已安装 Skill 或管理 Skill 状态时。",
                true,
                "SKILL.md",
                List.of("default", "install", "list"),
                List.of("file-read", "file-write", "network"),
                metadata);
        return new SkillPackage(manifest, """
                ---
                name: skills-install
                description: 安装、列出、启用、禁用 ClawAgent Skill。用于用户要求导入本地 Skill、从 JSON 安装 Skill、查看已安装 Skill、启用禁用 Skill 或管理 Skill 状态时。
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
                """);
    }
}
