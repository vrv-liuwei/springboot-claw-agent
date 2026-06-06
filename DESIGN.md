# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-06-02
- Primary product surfaces: React/Vite admin console at `/admin/`, legacy HTML console at `/console/index.html`.
- Evidence reviewed:
  - `claw-agent-admin/src/App.tsx`
  - `claw-agent-admin/src/styles.css`
  - `claw-agent-admin/src/api.ts`
  - `claw-agent-admin/src/types.ts`
  - Reference: `https://github.com/dongshuyan/openclaw-zh/tree/main/ui`

## Brand
- Personality: quiet enterprise console, technical, direct, observable.
- Trust signals: health status, version, runtime state, clear tool/task/Todo evidence.
- Avoid: marketing hero layouts, decorative gradients, copied OpenClaw menu names that do not match ClawAgent behavior.

## Product goals
- Goals: operate ClawAgent sessions, inspect runtime/tool/Skill/MCP state, and debug task execution without raw JSON-first screens.
- Non-goals: replace the legacy console immediately, implement unavailable backend APIs as fake data, or copy OpenClaw product semantics.
- Success signals: user can understand what the Agent is doing, which capability is enabled, where failures happened, and what remains unimplemented.

## Personas and jobs
- Primary personas: developer/operator integrating ClawAgent locally; maintainer debugging tools, Skills, MCP, ReAct, and token usage.
- User jobs: run chat tasks, inspect history, review task logs, verify tools and skills, configure runtime, and find missing backend support.
- Key contexts of use: localhost development, desktop browser, wide-screen admin view.

## Information architecture
- Primary navigation:
  - 对话: 聊天
  - 运行: 概览、会话、任务、Todo、Token
  - 能力: 工具能力、MCP Server、Skills
  - 系统: 配置、日志、节点
- Core routes/screens: chat workbench, overview metrics, history/session details, task table, Todo list, tool registry, MCP registry, Skill registry, token usage, config placeholder, logs placeholder, nodes placeholder.
- Content hierarchy: topbar status first, sidebar navigation second, page title/subtitle, then task-specific panels.

## Design principles
- Principle 1: Reference OpenClaw's calm dashboard rhythm, but use ClawAgent's own domain model.
- Principle 2: Every menu item must either display real data or a clear unavailable/empty state. Do not point a menu item to an unrelated page.
- Tradeoffs: keep the first admin version lightweight and repo-native, while leaving room for richer backend APIs later.

## Visual language
- Color: mostly neutral white/gray with red accent for active navigation and risk states.
- Typography: compact Microsoft YaHei/Arial, dashboard headings only where needed.
- Spacing/layout rhythm: 56px topbar, narrow sidebar, dense content cards, restrained gaps.
- Shape/radius/elevation: small radius, subtle borders, minimal shadows.
- Motion: no decorative motion in the current phase.
- Imagery/iconography: lucide icons; no unrelated mascot unless a real ClawAgent asset is introduced.

## Components
- Existing components to reuse: `Panel`, `Metric`, `Table`, `StatusPill`, chat bubbles, Todo cards.
- New/changed components: navigation metadata, unavailable/placeholder panels, page-specific render branches.
- Variants and states: loading, empty, unavailable, success, warning, danger, active navigation.
- Token/component ownership: CSS stays in `claw-agent-admin/src/styles.css` until a component library is introduced.

## Accessibility
- Target standard: practical keyboard and contrast support for developer console use.
- Keyboard/focus behavior: buttons and inputs must remain native focusable.
- Contrast/readability: avoid low-contrast gray on white for core labels.
- Screen-reader semantics: use semantic headings, tables, and buttons.
- Reduced motion and sensory considerations: avoid unnecessary animation.

## Responsive behavior
- Supported breakpoints/devices: primary target is desktop width; mobile is not the first optimization target.
- Layout adaptations: keep content scrollable and avoid page-wide horizontal overflow where possible.
- Touch/hover differences: hover affordances are optional; click targets should remain visible.

## Interaction states
- Loading: use disabled refresh/actions and stable placeholders.
- Empty: show specific empty-state copy per page.
- Error: show page-level request failure banner.
- Success: show green pills/dots for completed or connected states.
- Disabled: show unavailable pages as explicitly not connected to backend yet.
- Offline/slow network: chat and tool calls should keep visible progress and failure states.

## Content voice
- Tone: concise Chinese, operational and factual.
- Terminology: use ClawAgent terms: 会话、任务、Todo、工具能力、MCP Server、Skill、Token、配置、日志、节点.
- Microcopy rules: do not use OpenClaw-only concepts such as WhatsApp channel or gateway token unless ClawAgent implements them.

## Implementation constraints
- Framework/styling system: React + Vite + plain CSS + lucide-react.
- Design-token constraints: no new UI framework until requested.
- Performance constraints: avoid large JSON blocks by default in dashboard views.
- Compatibility constraints: legacy `/console/index.html` remains available.
- Test/screenshot expectations: run `npm run build`, `mvn compile -DskipTests`, and browser visual check for major UI changes.

## Open questions
- [ ] Should config/log/node pages get dedicated backend APIs in M3, or remain read-only placeholders until M4/M5?
