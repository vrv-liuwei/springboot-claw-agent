import { Presentation, PresentationFile } from "@oai/artifact-tool";

const FINAL_PPTX = process.argv[2] || "D:/workspace/codex/springboot-claw-agent/docs/clawagent-project-overview.pptx";

const W = 1280;
const H = 720;
const C = {
  ink: "#25283d",
  muted: "#4b5563",
  sub: "#6b7280",
  blue: "#0078f0",
  navy: "#25283d",
  teal: "#2fa69a",
  amber: "#d6ad2c",
  red: "#ef2f4f",
  green: "#34a853",
  line: "#d4d8de",
  soft: "#eef3f6",
  white: "#ffffff",
  paleBlue: "#eef7ff",
  paleTeal: "#edf8f6",
  paleAmber: "#fff7df",
  paleRed: "#fff0f2",
  paleGray: "#f7f9fb",
};

const presentation = Presentation.create({ slideSize: { width: W, height: H } });
presentation.theme.colorScheme = {
  name: "ClawAgent Office",
  themeColors: {
    accent1: C.blue,
    accent2: C.teal,
    accent3: C.amber,
    accent4: C.red,
    accent5: "#64748b",
    accent6: C.green,
    bg1: C.white,
    bg2: C.paleGray,
    tx1: C.ink,
    tx2: C.muted,
    dk1: "#000000",
    dk2: C.navy,
    lt1: C.white,
    lt2: C.soft,
    hlink: C.blue,
    folHlink: "#7c3aed",
  },
};

function shape(slide, geometry, position, fill, line = "none", name = "") {
  return slide.shapes.add({
    geometry,
    name,
    position,
    fill,
    line: line === "none" ? { style: "solid", fill: "none", width: 0 } : line,
  });
}

function text(slide, value, position, opts = {}) {
  const t = shape(slide, "textbox", position, "none", "none", opts.name || "");
  t.text = value;
  t.text.style = {
    typeface: "Microsoft YaHei",
    fontSize: opts.size ?? 20,
    bold: opts.bold ?? false,
    color: opts.color ?? C.ink,
    alignment: opts.align ?? "left",
    verticalAlignment: opts.valign ?? "top",
    lineSpacing: opts.lineSpacing ?? 1.08,
    wrap: "square",
    insets: opts.insets ?? { top: 0, right: 0, bottom: 0, left: 0 },
  };
  return t;
}

function bullets(slide, items, position, opts = {}) {
  const t = shape(slide, "textbox", position, "none", "none", opts.name || "");
  t.text.set(items.map((item) => ({
    bulletCharacter: opts.bullet ?? "•",
    marginLeft: opts.marginLeft ?? 22,
    indent: opts.indent ?? -12,
    spaceAfter: opts.spaceAfter ?? 7,
    runs: Array.isArray(item) ? item : [item],
  })));
  t.text.style = {
    typeface: "Microsoft YaHei",
    fontSize: opts.size ?? 19,
    color: opts.color ?? C.muted,
    lineSpacing: opts.lineSpacing ?? 1.15,
    wrap: "square",
    insets: { top: 0, right: 0, bottom: 0, left: 0 },
  };
  return t;
}

function addHeader(slide, title, subtitle, pageNo) {
  shape(slide, "rect", { left: 0, top: 0, width: W, height: 8 }, C.blue);
  text(slide, title, { left: 58, top: 44, width: 900, height: 56 }, { size: 36, bold: true });
  if (subtitle) text(slide, subtitle, { left: 60, top: 99, width: 980, height: 32 }, { size: 16, color: C.muted });
  shape(slide, "roundRect", { left: 1172, top: 653, width: 44, height: 28 }, C.navy, "none").borderRadius = "rounded-md";
  text(slide, String(pageNo).padStart(2, "0"), { left: 1172, top: 659, width: 44, height: 16 }, { size: 12, bold: true, color: C.white, align: "center" });
}

function card(slide, x, y, w, h, title, body, opts = {}) {
  const fill = opts.fill ?? C.white;
  const accent = opts.accent ?? C.blue;
  const box = shape(slide, "roundRect", { left: x, top: y, width: w, height: h }, fill, { style: "solid", fill: C.line, width: 1 }, title);
  box.borderRadius = "rounded-lg";
  shape(slide, "rect", { left: x, top: y, width: 8, height: h }, accent);
  text(slide, title, { left: x + 24, top: y + 20, width: w - 44, height: 30 }, { size: opts.titleSize ?? 22, bold: true });
  if (Array.isArray(body)) {
    bullets(slide, body, { left: x + 28, top: y + 62, width: w - 48, height: h - 72 }, { size: opts.bodySize ?? 18, spaceAfter: opts.spaceAfter ?? 7 });
  } else {
    text(slide, body, { left: x + 24, top: y + 60, width: w - 44, height: h - 72 }, { size: opts.bodySize ?? 18, color: C.muted, lineSpacing: opts.lineSpacing ?? 1.14 });
  }
  return box;
}

function pill(slide, label, x, y, w, fill = C.soft, color = C.ink) {
  const p = shape(slide, "roundRect", { left: x, top: y, width: w, height: 38 }, fill, { style: "solid", fill: C.line, width: 1 }, label);
  p.borderRadius = "rounded-md";
  text(slide, label, { left: x + 8, top: y + 10, width: w - 16, height: 18 }, { size: 15, bold: true, color, align: "center" });
  return p;
}

function addSource(slide, s) {
  text(slide, s, { left: 72, top: 642, width: 920, height: 18 }, { size: 12, color: C.sub });
}

function addTitleSlide() {
  const slide = presentation.slides.add();
  shape(slide, "rect", { left: 0, top: 0, width: W, height: 8 }, C.blue);
  shape(slide, "rect", { left: 0, top: 8, width: 410, height: 712 }, C.paleBlue);
  shape(slide, "rect", { left: 410, top: 8, width: 10, height: 712 }, C.blue);
  text(slide, "ClawAgent", { left: 72, top: 86, width: 360, height: 58 }, { size: 46, bold: true });
  text(slide, "Java Harness Agent Runtime", { left: 72, top: 152, width: 360, height: 32 }, { size: 21, color: C.muted });
  text(slide, "面向 Spring Boot 业务系统落地的企业级本地行动 Agent", { left: 72, top: 206, width: 330, height: 80 }, { size: 22, color: C.ink, lineSpacing: 1.18 });
  card(slide, 486, 128, 610, 104, "定位", "可独立运行、可嵌入业务系统、可审计、可恢复、可扩展的 Spring Boot Runtime。", { accent: C.blue, bodySize: 18 });
  card(slide, 486, 268, 610, 104, "新增重点", "计划模式、自动化任务、通道与附件、权限治理、Worker/SubAgent、MCP/Skill 生态已进入主链路。", { accent: C.teal, bodySize: 18 });
  card(slide, 486, 408, 610, 104, "核心判断", "它不是通用 Agent 生态平台，而是把 Agent 能力接入 Java 企业应用的 Runtime 内核。", { accent: C.amber, bodySize: 18 });
  text(slide, "项目文档 PPT", { left: 72, top: 600, width: 360, height: 26 }, { size: 18, color: C.sub });
  text(slide, "2026-07", { left: 72, top: 632, width: 160, height: 20 }, { size: 14, color: C.sub });
}

function addAgenda() {
  const slide = presentation.slides.add();
  addHeader(slide, "目录", "按业务能力和 Agent 技术栈重新组织", 2);
  const rows = [
    ["01", "Agent 技术栈视角", "参考图1说明模型、记忆、工具、Runtime、安全治理五层"],
    ["02", "ClawAgent 架构", "项目架构图、系统架构图和执行链路"],
    ["03", "新增业务功能", "Plan、自动化、通道附件、MCP/Skill、权限、Worker/SubAgent"],
    ["04", "场景与竞品比较", "使用场景、OpenClaw/Hermes/AgentScope 比较、优缺点和边界"],
  ];
  rows.forEach((r, i) => {
    const y = 156 + i * 112;
    text(slide, r[0], { left: 88, top: y, width: 64, height: 40 }, { size: 28, bold: true, color: C.blue, align: "center" });
    text(slide, r[1], { left: 176, top: y, width: 260, height: 36 }, { size: 25, bold: true });
    text(slide, r[2], { left: 460, top: y + 5, width: 640, height: 30 }, { size: 19, color: C.muted });
    shape(slide, "rect", { left: 86, top: y + 60, width: 1020, height: 1.2 }, C.line);
  });
}

function addAgentStack() {
  const slide = presentation.slides.add();
  addHeader(slide, "按图1视角：Agent 技术栈覆盖情况", "从现代 Agent 通用分层看 ClawAgent 已覆盖哪些主链路", 3);
  const layers = [
    ["模型层", C.blue, "OpenAI-compatible / DeepSeek / Qwen / GLM 等兼容接入；Spring AI 可选适配；支持 chat、embeddings、vision 最终回复。"],
    ["记忆层", C.teal, "SQLite FTS5 + JVector + RRF 混合检索；命中记录、候选审核、长期记忆管理页。"],
    ["工具层", C.amber, "内置 time/weather/web/filesystem/process/execute；业务 AgentTool Bean；MCP tools；Skill tools。"],
    ["Runtime 层", C.navy, "DefaultAgentRuntime 管会话、任务、步骤、事件、ReAct、Tool Calling、Plan 执行、SSE。"],
    ["安全治理层", C.red, "ToolGuard、PendingAction、角色策略、API Token、设备、通道绑定、Rate Limit、审计与脱敏。"],
  ];
  layers.forEach((l, i) => {
    const y = 140 + i * 86;
    shape(slide, "roundRect", { left: 72, top: y, width: 180, height: 58 }, i % 2 ? C.paleTeal : C.paleBlue, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
    shape(slide, "rect", { left: 72, top: y, width: 8, height: 58 }, l[1]);
    text(slide, l[0], { left: 102, top: y + 16, width: 110, height: 26 }, { size: 23, bold: true, align: "center" });
    text(slide, l[2], { left: 282, top: y + 10, width: 850, height: 44 }, { size: 19, color: C.muted, lineSpacing: 1.12 });
  });
  card(slide, 930, 134, 230, 402, "结论", [
    "ClawAgent 不是大而全生态。",
    "强项在 Java 业务落地。",
    "评测平台、分布式观测、云沙箱仍是后续边界。",
  ], { accent: C.green, fill: C.paleGray, bodySize: 18 });
  addSource(slide, "参考：用户提供的 Agent 技术栈图；本项目 README / CONFIGURATION / pom.xml。");
}

function addProjectArchitecture() {
  const slide = presentation.slides.add();
  addHeader(slide, "项目架构图", "从入口层、Runtime、能力扩展、数据与治理四层看模块分工", 4);
  const left = 72;
  const layerW = 184;
  const startX = 292;
  const boxW = 132;
  const gapX = 22;
  const rows = [
    ["入口与界面", C.blue, ["REST API", "Web Console", "React Admin", "Desktop App", "CLI", "Channel 入站"]],
    ["运行主链路", C.navy, ["AgentRuntime", "Session/Task/Step", "Planner Router", "ReAct Loop", "Tool Calling", "SSE Event"]],
    ["能力扩展", C.teal, ["Toolkit", "MCP Client", "Skill Executor", "AgentTool Bean", "SubAgent", "Worker"]],
    ["模型与知识", C.amber, ["OpenAI-compatible", "Spring AI optional", "Memory", "Knowledge", "Attachment", "Embedding/JVector"]],
    ["治理与持久化", C.red, ["SQLite Store", "ToolGuard", "PendingAction", "Runtime Interceptor", "Auth/Device/Token", "Audit/RateLimit"]],
  ];
  rows.forEach((r, i) => {
    const y = 126 + i * 92;
    shape(slide, "roundRect", { left, top: y, width: layerW, height: 56 }, i % 2 ? C.navy : C.blue, "none").borderRadius = "rounded-md";
    text(slide, r[0], { left, top: y + 17, width: layerW, height: 20 }, { size: 17, bold: true, color: C.white, align: "center" });
    r[2].forEach((name, j) => {
      const x = startX + j * (boxW + gapX);
      shape(slide, "roundRect", { left: x, top: y, width: boxW, height: 56 }, C.white, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-md";
      text(slide, name, { left: x + 6, top: y + 16, width: boxW - 12, height: 22 }, { size: name.length > 15 ? 11 : 13, bold: true, align: "center" });
    });
  });
  text(slide, "关键设计：core / spi 不绑定 Spring；starter 负责装配；runtime 只依赖 Registry、SPI、工具、MCP、Skill、Channel 抽象。", { left: 76, top: 616, width: 1020, height: 24 }, { size: 15, color: C.muted });
}

function addSystemArchitecture() {
  const slide = presentation.slides.add();
  addHeader(slide, "系统架构图", "围绕 Spring Boot Runtime 的入口、执行、扩展、治理、存储和模型生态", 5);
  card(slide, 60, 146, 190, 360, "入口与通道", ["Web Console", "React Admin", "App / CLI", "REST API", "飞书/钉钉/DDIO"], { accent: C.blue, bodySize: 16, spaceAfter: 10 });
  card(slide, 1008, 146, 190, 360, "管理与治理", ["Admin 配置", "审批策略", "Audit Log", "Token 统计", "设备与角色"], { accent: C.amber, bodySize: 16, spaceAfter: 10 });
  const center = shape(slide, "roundRect", { left: 286, top: 136, width: 690, height: 384 }, C.paleBlue, { style: "solid", fill: "#8db9ff", width: 1 });
  center.borderRadius = "rounded-lg";
  shape(slide, "rect", { left: 286, top: 136, width: 690, height: 12 }, C.navy);
  text(slide, "ClawAgent Server / Spring Boot", { left: 360, top: 162, width: 520, height: 28 }, { size: 22, bold: true, align: "center" });
  ["API Layer", "Channel Gateway", "Admin Service"].forEach((p, i) => pill(slide, p, 340 + i * 200, 214, 162, C.white));
  shape(slide, "roundRect", { left: 330, top: 276, width: 590, height: 110 }, C.white, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
  text(slide, "DefaultAgentRuntime", { left: 452, top: 296, width: 340, height: 28 }, { size: 23, bold: true, align: "center" });
  ["Planner", "Session / Task", "SSE Event"].forEach((p, i) => pill(slide, p, 370 + i * 178, 340, 146, C.soft));
  shape(slide, "roundRect", { left: 330, top: 408, width: 590, height: 84 }, C.white, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
  ["ToolRegistry", "ToolGuard", "Interceptor"].forEach((p, i) => pill(slide, p, 370 + i * 178, 436, 146, C.soft));
  shape(slide, "roundRect", { left: 60, top: 550, width: 1138, height: 80 }, C.white, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
  shape(slide, "rect", { left: 60, top: 550, width: 1138, height: 12 }, C.teal);
  text(slide, "数据、模型与扩展生态", { left: 510, top: 570, width: 230, height: 22 }, { size: 17, bold: true, align: "center" });
  ["SQLite", "Memory / JVector", "Workspace", "OpenAI-compatible", "MCP / Skills"].forEach((p, i) => pill(slide, p, 112 + i * 214, 596, 168, C.soft));
}

function addRuntimeFlow() {
  const slide = presentation.slides.add();
  addHeader(slide, "任务执行链路", "默认 ReAct 主链路，同时兼容简单问答和原生 Tool Calling", 6);
  const steps = [
    ["1", "接收请求", "REST / UI / Channel\n携带 user、device、session"],
    ["2", "策略前置", "身份、RateLimit、系统意图\nPendingAction 检查"],
    ["3", "规划执行", "ReAct / Tool Calling\n生成 Todo 与工具调用"],
    ["4", "工具治理", "ToolGuard 风险判断\n审批、隔离、审计"],
    ["5", "观察与续跑", "保存 step/event\n失败可 checkpoint/resume"],
    ["6", "最终回复", "流式 llm.delta\nToken 和审计聚合"],
  ];
  steps.forEach((s, i) => {
    const x = 66 + i * 194;
    shape(slide, "roundRect", { left: x, top: 168, width: 152, height: 150 }, C.white, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
    shape(slide, "ellipse", { left: x + 14, top: 184, width: 34, height: 34 }, i % 2 ? C.teal : C.blue, "none");
    text(slide, s[0], { left: x + 14, top: 192, width: 34, height: 14 }, { size: 14, bold: true, color: C.white, align: "center" });
    text(slide, s[1], { left: x + 18, top: 232, width: 116, height: 24 }, { size: 20, bold: true, align: "center" });
    text(slide, s[2], { left: x + 14, top: 266, width: 124, height: 44 }, { size: 14, color: C.muted, align: "center", lineSpacing: 1.05 });
    if (i < steps.length - 1) shape(slide, "rightArrow", { left: x + 158, top: 228, width: 28, height: 28 }, C.soft, { style: "solid", fill: C.line, width: 1 });
  });
  card(slide, 74, 386, 520, 138, "为什么默认 ReAct", "用户输入不可预测，ReAct 能先思考、再调用工具、再观察结果并继续决策，适合复杂任务和多工具链路。", { accent: C.blue, bodySize: 20 });
  card(slide, 674, 386, 520, 138, "如何兼容 single / tool-calling", "简单意图可走一次模型或直接工具；模型支持原生 tool_calls 时走 Tool Calling；复杂任务仍回到 ReAct。", { accent: C.teal, bodySize: 20 });
}

function addFeatureOverview() {
  const slide = presentation.slides.add();
  addHeader(slide, "新增业务功能总览", "从“能跑 Agent”升级到“能管、能审、能自动化、能接入业务”", 7);
  const cards = [
    ["Plan 计划模式", C.blue, "先生成计划，再修订、确认、执行；支持模板和差异摘要。"],
    ["自动化任务", C.teal, "固定间隔、Cron、单次执行、立即运行、重试退避和历史记录。"],
    ["通道与附件", C.amber, "飞书/钉钉/DDIO，媒体缓存、附件解析、富文本摘要。"],
    ["身份与权限", C.red, "本地用户、API Token、设备配对、通道绑定、角色策略。"],
    ["Worker/SubAgent", C.navy, "高危命令独立 JVM 隔离，子 Agent 单个/批量/并行派发。"],
    ["MCP/Skill 生态", C.green, "STDIO/HTTP/SSE MCP，Skill 安装启停和多执行器。"],
  ];
  cards.forEach((c, i) => {
    const x = 70 + (i % 3) * 380;
    const y = 146 + Math.floor(i / 3) * 202;
    card(slide, x, y, 330, 150, c[0], c[2], { accent: c[1], fill: i % 2 ? C.paleGray : C.white, bodySize: 19 });
  });
  text(slide, "业务价值：降低接入成本、把高危动作纳入审批和隔离、让 IM/桌面/后台任务共享同一套 Runtime。", { left: 96, top: 592, width: 1030, height: 30 }, { size: 20, bold: true, color: C.ink, align: "center" });
}

function addPlanMode() {
  const slide = presentation.slides.add();
  addHeader(slide, "Plan 计划模式", "适合复杂需求：先计划、可修改、再执行，而不是直接开跑", 8);
  const steps = ["需求输入", "生成 PlanDraft", "用户修订/确认", "转换 Todo", "执行 SSE", "审计差异"];
  steps.forEach((s, i) => {
    const x = 78 + i * 178;
    pill(slide, s, x, 176, 142, i < 2 ? C.paleBlue : i < 4 ? C.paleTeal : C.paleAmber, C.ink);
    if (i < steps.length - 1) shape(slide, "rightArrow", { left: x + 148, top: 181, width: 24, height: 28 }, C.soft, { style: "solid", fill: C.line, width: 1 });
  });
  card(slide, 76, 284, 342, 194, "已经具备", ["PlanDraft / PlanItem / PlanStore", "SQLite agent_plan 持久化", "生成、修订、确认、取消、执行", "内置 local-dev / bugfix 等模板"], { accent: C.blue, bodySize: 18 });
  card(slide, 468, 284, 342, 194, "前端体验", ["Admin/App 支持计划开关", "/plan 指令直接创建计划", "计划卡片展示版本差异", "执行阻塞时可修订后继续"], { accent: C.teal, bodySize: 18 });
  card(slide, 860, 284, 342, 194, "设计边界", ["不另造第二套执行引擎", "确认后复用 Runtime/Todo/ToolGuard", "高危步骤仍走审批和隔离", "后续优化自动重规划策略"], { accent: C.amber, bodySize: 18 });
  addSource(slide, "来源：README Plan API / 当前完成情况。");
}

function addAutomation() {
  const slide = presentation.slides.add();
  addHeader(slide, "智能体自动化任务", "把一次性 Agent 调用扩展成可运维的周期任务", 9);
  card(slide, 70, 148, 335, 330, "调度能力", ["固定间隔执行", "Cron 表达式", "单次计划任务", "立即运行", "启用/暂停/编辑"], { accent: C.blue, bodySize: 20 });
  card(slide, 470, 148, 335, 330, "失败治理", ["失败重试", "退避策略", "重试耗尽后暂停", "运行历史保留", "异常链路可追踪"], { accent: C.red, bodySize: 20 });
  card(slide, 870, 148, 335, 330, "运营指标", ["耗时聚合", "Token 聚合", "工具链路聚合", "管理台页面", "适合巡检/日报/同步任务"], { accent: C.teal, bodySize: 20 });
  text(slide, "典型场景：每天巡检接口状态、定时总结群消息、定期扫描项目文档、失败后自动重试并保留审计记录。", { left: 96, top: 558, width: 1020, height: 30 }, { size: 20, bold: true, color: C.ink, align: "center" });
}

function addChannelKnowledge() {
  const slide = presentation.slides.add();
  addHeader(slide, "通道、附件与知识库", "让 IM 消息、文件和本地知识进入同一条 Agent 执行链路", 10);
  card(slide, 70, 142, 350, 184, "通道入口", ["飞书、钉钉、DDIO", "HTTP / Stream / Bot 标准事件", "Channel 外部用户绑定本地用户", "事件语义 metadata"], { accent: C.blue, bodySize: 18 });
  card(slide, 465, 142, 350, 184, "媒体与附件", ["媒体 URL / 大小 / 超时限制", "钉钉 downloadCode 下载", "附件存在标记与下载状态", "富文本统一 Markdown 摘要"], { accent: C.amber, bodySize: 18 });
  card(slide, 860, 142, 350, 184, "知识与记忆", ["AttachmentService 写入知识库", "KnowledgeService 补充上下文", "SQLite FTS5 + JVector", "命中记录和候选审核"], { accent: C.teal, bodySize: 18 });
  shape(slide, "roundRect", { left: 115, top: 402, width: 1020, height: 86 }, C.paleGray, { style: "solid", fill: C.line, width: 1 }).borderRadius = "rounded-lg";
  text(slide, "处理路径：通道消息 -> ChannelRouter -> PendingAction / 系统意图 -> 附件解析与知识检索 -> AgentRuntime -> 回复到原通道", { left: 140, top: 432, width: 970, height: 26 }, { size: 20, bold: true, align: "center" });
  addSource(slide, "来源：README 通道、系统意图、附件与知识库说明；CONFIGURATION 文档。");
}

function addMcpSkillToolkit() {
  const slide = presentation.slides.add();
  addHeader(slide, "MCP、Skill 与 Toolkit 生态", "统一注册到 AgentToolRegistry，业务工具和外部工具同一套治理", 11);
  card(slide, 72, 146, 340, 350, "MCP", ["STDIO 启动、握手、tools/list、tools/call", "HTTP / streamableHttp JSON-RPC", "SSE endpoint/message 兼容", "resources/prompts 查询读取", "autoApprove 通配规则"], { accent: C.blue, bodySize: 18 });
  card(slide, 470, 146, 340, 350, "Skill", ["本地安装、启用、禁用、列表", "manifest 保存到 .clawagent/skills", "document / http / script / java 执行器", "权限声明校验", "系统 Skill: create/install"], { accent: C.teal, bodySize: 18 });
  card(slide, 868, 146, 340, 350, "Toolkit", ["time / weather / web.fetch / web.search", "filesystem 读、列、搜、信息、受控写", "execute 与 process.start", "业务 AgentTool Bean 自动发现", "高危工具接入 ToolGuard"], { accent: C.amber, bodySize: 18 });
  text(slide, "落地重点：不是把工具分散在不同插件里，而是统一注册、统一审批、统一审计、统一事件流。", { left: 124, top: 568, width: 1010, height: 28 }, { size: 20, bold: true, align: "center" });
}

function addSecurity() {
  const slide = presentation.slides.add();
  addHeader(slide, "身份、权限与审批治理", "从单机 Demo 走向可管可审的本地企业 Agent", 12);
  const items = [
    ["身份体系", C.blue, ["本地 owner 初始化", "用户登录/退出/会话撤销", "API Token owner/scope", "当前用户注入任务 userId"]],
    ["设备与通道", C.teal, ["设备配对、密钥校验、心跳", "设备用户绑定", "Channel 外部用户绑定", "Token/User/Device/Channel 合并"]],
    ["审批与策略", C.red, ["PendingAction 三类确认", "高危工具完整确认文本", "角色策略默认审批模式", "Agent 子任务工具边界"]],
    ["入口保护", C.amber, ["单机固定窗口 Rate Limit", "按 Token/User/Device/IP 分桶", "Prompt Injection Defense", "SensitiveDataInterceptor 脱敏"]],
  ];
  items.forEach((it, i) => {
    const x = 74 + (i % 2) * 570;
    const y = 144 + Math.floor(i / 2) * 220;
    card(slide, x, y, 510, 168, it[0], it[2], { accent: it[1], bodySize: 18 });
  });
  addSource(slide, "来源：README 当前完成情况、权限治理、通道 Channel 说明。");
}

function addWorkerSubAgent() {
  const slide = presentation.slides.add();
  addHeader(slide, "Worker 与 SubAgent", "把高危执行和并行子任务从主 Runtime 中隔离出来", 13);
  card(slide, 76, 146, 340, 362, "高危命令 Worker", ["execute high risk 才启用", "独立 JVM worker 进程", "超时强杀、输出上限", "JVM heap / CPU 时间限制", "主服务侧 worker 并发池"], { accent: C.red, bodySize: 18 });
  card(slide, 470, 146, 340, 362, "process.start 隔离", ["后台进程可通过 worker 启动", "返回 pid 后由主服务托管", "记录 worker pid/exitCode/elapsedMs", "stdout/stderr 截断信息回传", "便于审计和排障"], { accent: C.amber, bodySize: 18 });
  card(slide, 864, 146, 340, 362, "SubAgent 编排", ["单个派生、批量派发、并行提交", "默认只读继承项目上下文", "parallel 默认最多 4，硬上限 8", "from-plan 可把 PlanItem 转子任务", "高危审批不横向扩散"], { accent: C.blue, bodySize: 18 });
  text(slide, "边界：worker 不做第二套完整 Runtime；它只负责“在哪里执行、怎么限制、怎么杀掉”。", { left: 124, top: 580, width: 1010, height: 28 }, { size: 20, bold: true, align: "center" });
}

function addOpsUi() {
  const slide = presentation.slides.add();
  addHeader(slide, "管理台、App 与运维视角", "前端不只是聊天框，而是把配置、审计、能力边界和本地运行状态显性化", 14);
  card(slide, 72, 140, 260, 378, "Admin Console", ["总览指标", "会话/任务/Todo", "MCP / Skill", "Token usage", "审计/自动化/文件审查"], { accent: C.blue, bodySize: 18 });
  card(slide, 366, 140, 260, 378, "Desktop App", ["聊天工作台", "计划模式开关", "文件审查", "设置页", "设备配对/心跳"], { accent: C.teal, bodySize: 18 });
  card(slide, 660, 140, 260, 378, "Web Console", ["保留轻量入口", "适合快速验证", "REST/SSE 流式任务", "本地服务端口 17891", "Health API"], { accent: C.amber, bodySize: 18 });
  card(slide, 954, 140, 260, 378, "运维页面", ["本地配置健康检查", "Worker jar 路径检查", "Channel 绑定管理", "Auth 角色门禁", "异常任务与日志"], { accent: C.red, bodySize: 18 });
  addSource(slide, "来源：README 管理台、App、当前完成情况。");
}

function addObservability() {
  const slide = presentation.slides.add();
  addHeader(slide, "审计、记忆与运行数据", "让 Agent 每一步都能被复盘，而不是只看到最终回答", 15);
  card(slide, 82, 148, 330, 322, "任务事件", ["task.started / completed", "step.started / finished", "tool started / succeeded", "llm.delta / llm.completed", "plan.updated"], { accent: C.blue, bodySize: 18 });
  card(slide, 475, 148, 330, 322, "审计与回放", ["Audit Log / 事件回放", "工具审批记录", "文件审查 / 回滚", "任务恢复和 checkpoint", "敏感字段脱敏"], { accent: C.red, bodySize: 18 });
  card(slide, 868, 148, 330, 322, "Token 与记忆", ["按 model / phase 聚合", "prompt/completion/total tokens", "FTS5 + JVector + RRF", "命中记录", "候选审核管理"], { accent: C.teal, bodySize: 18 });
  text(slide, "对企业来说，关键不是 Agent 能不能答，而是它为什么这么做、调用了什么、谁批准的、能不能回放。", { left: 112, top: 552, width: 1040, height: 28 }, { size: 20, bold: true, align: "center" });
}

function addScenarios() {
  const slide = presentation.slides.add();
  addHeader(slide, "多种使用场景", "围绕 Java 企业系统、本地行动和 IM 工作流落地", 16);
  const cards = [
    ["企业系统内嵌 Agent", "Spring Boot starter 自动装配业务 AgentTool Bean，复用企业权限和审计。"],
    ["本地开发行动助手", "读取项目文件、搜索、执行低危命令，高危动作走审批和 worker 隔离。"],
    ["IM 协同入口", "飞书/钉钉/DDIO 消息进入 ChannelRouter，支持附件与富文本摘要。"],
    ["计划型复杂任务", "先生成计划，用户确认后执行，阻塞后可修订继续。"],
    ["定时巡检与自动化", "Cron/间隔/单次任务，失败重试和运行历史保留。"],
    ["知识问答与文件审查", "附件入库、知识检索、文件审查、diff 和回滚进入同一任务链路。"],
  ];
  cards.forEach((c, i) => {
    const x = 70 + (i % 3) * 380;
    const y = 144 + Math.floor(i / 3) * 202;
    card(slide, x, y, 330, 150, c[0], c[1], { accent: [C.blue, C.teal, C.amber, C.red, C.navy, C.green][i], bodySize: 18 });
  });
}

function addCompetitorOpenClaw() {
  const slide = presentation.slides.add();
  addHeader(slide, "与 OpenClaw 对比", "OpenClaw 偏个人多通道助手；ClawAgent 偏 Java 企业 Runtime", 17);
  card(slide, 74, 144, 340, 360, "ClawAgent", ["Java 17 / Spring Boot / Maven 多模块", "可嵌入业务系统", "ToolGuard、审计、审批、脱敏", "Plan/Automation/Worker/SubAgent", "适合企业内应用集成"], { accent: C.blue, bodySize: 18 });
  card(slide, 470, 144, 340, 360, "OpenClaw", ["个人 AI Assistant / Local-first Gateway", "多通道覆盖更突出", "移动/语音/Canvas 体验更强", "生态偏个人助手", "企业 Java 内嵌不是重点"], { accent: C.teal, bodySize: 18 });
  card(slide, 866, 144, 340, 360, "选型判断", ["做 Java 企业系统集成：ClawAgent 更顺手", "做个人助手和多通道常驻体验：OpenClaw 更完整", "两者都重视安全，但治理粒度不同"], { accent: C.amber, bodySize: 18 });
  text(slide, "核心判断：OpenClaw 的强项是个人多通道常驻体验；ClawAgent 的强项是 Java 企业系统里的可嵌入、可审计、可治理。", { left: 96, top: 568, width: 1040, height: 30 }, { size: 20, bold: true, align: "center" });
}

function addCompetitorHermesAgentScope() {
  const slide = presentation.slides.add();
  addHeader(slide, "与 Hermes Agent / AgentScope 对比", "一个偏产品化自改进，一个偏通用生态栈，ClawAgent 偏企业应用内核", 18);
  card(slide, 72, 142, 330, 382, "Hermes Agent", ["Nous Research 自改进 Agent", "学习循环、技能自创建/自改进", "跨平台工具生态更成熟", "适合产品化 Agent 能力", "ClawAgent 更偏 Java 业务底座"], { accent: C.amber, bodySize: 18 });
  card(slide, 474, 142, 330, 382, "AgentScope", ["通用 Agent 全生命周期栈", "开发、运行、记忆、评测、训练", "多 Agent 抽象更成熟", "生态广度更完整", "ClawAgent 更贴 Spring Boot 落地"], { accent: C.teal, bodySize: 18 });
  card(slide, 876, 142, 330, 382, "ClawAgent", ["Java Harness Agent Runtime", "管理台 + Starter + Server", "权限、审计、本地执行更深", "MCP/Skill/业务工具统一治理", "适合现有 Java 系统接入"], { accent: C.blue, bodySize: 18 });
  text(slide, "一句话：AgentScope 像平台型框架生态，Hermes 像产品化 Agent，ClawAgent 像企业应用内核 + Spring Boot starter。", { left: 94, top: 580, width: 1050, height: 28 }, { size: 20, bold: true, align: "center" });
}

function addProsCons() {
  const slide = presentation.slides.add();
  addHeader(slide, "项目优缺点", "结合当前仓库成熟度和竞品差异看选型边界", 19);
  card(slide, 72, 142, 540, 376, "主要优点", ["Java / Spring Boot 原生，适合已有企业后端集成", "Runtime、Planner、工具、MCP、Skill、Channel 分层清晰", "计划模式、审批、审计、Worker 隔离已经进入主链路", "SQLite + 本地记忆 + JVector，单机可直接跑通", "Admin/App/Web 三端能支撑配置、运维和调试"], { accent: C.teal, bodySize: 20, spaceAfter: 10 });
  card(slide, 670, 142, 540, 376, "主要不足", ["外部向量库、Redis、分布式部署仍不是主能力", "评测/训练/多 Agent 生态不如 AgentScope 完整", "代码沙箱和 OS 级隔离还需继续增强", "企业级组织/用户组权限矩阵还在后续方向", "当前仓库迭代较快，管理台页面需持续跟随后端能力"], { accent: C.red, bodySize: 20, spaceAfter: 10 });
  text(slide, "适合优先选择 ClawAgent 的场景：Java 企业应用内嵌、本地行动 Agent、需要审批/审计/脱敏/文件回滚的受控自动化。", { left: 96, top: 584, width: 1020, height: 28 }, { size: 19, bold: true, align: "center" });
}

function addRoadmap() {
  const slide = presentation.slides.add();
  addHeader(slide, "后续演进建议", "保持 Java 企业落地主线，补齐生态广度和生产化能力", 20);
  card(slide, 94, 158, 316, 316, "短期", ["优化 Planner 动态路由", "增强 Plan 自动重规划", "补管理台缺失页面", "完善通道审批策略"], { accent: C.blue, bodySize: 20 });
  card(slide, 482, 158, 316, 316, "中期", ["接入 Redis / 分布式任务", "外部 VectorStore 适配", "企业权限矩阵编辑器", "更强观测和评测报表"], { accent: C.teal, bodySize: 20 });
  card(slide, 870, 158, 316, 316, "长期", ["OS 级沙箱和资源隔离", "多 Agent 编排策略成熟化", "组织级审计解释", "插件/Skill 市场化管理"], { accent: C.amber, bodySize: 20 });
  text(slide, "建议不要偏离主定位：优先做企业应用内核，把权限、审计、工具治理、Runtime 稳定性打磨扎实。", { left: 112, top: 562, width: 1030, height: 28 }, { size: 20, bold: true, align: "center" });
}

function addSources() {
  const slide = presentation.slides.add();
  addHeader(slide, "资料来源与说明", "内容以当前仓库文档与源码结构为主，并结合竞品公开定位做判断", 21);
  card(slide, 88, 148, 500, 320, "项目来源", ["README.md", "CONFIGURATION.md", "pom.xml Maven modules", "claw-agent-worker/PLAN.md", "当前仓库新增模块与接口命名"], { accent: C.blue, bodySize: 20 });
  card(slide, 680, 148, 500, 320, "外部比较说明", ["OpenClaw：按个人多通道助手定位比较", "Hermes Agent：按自改进 Agent 产品定位比较", "AgentScope：按通用 Agent 生态/框架栈比较", "竞品判断用于选型，不作为功能完全等价声明"], { accent: C.teal, bodySize: 20 });
  text(slide, "版本口径：根据当前仓库 README 的已完成能力和本次需求整理，适合作为项目介绍、内部评审和软著/架构沟通材料。", { left: 108, top: 560, width: 1040, height: 52 }, { size: 20, color: C.ink, align: "center", lineSpacing: 1.14 });
}

[
  addTitleSlide,
  addAgenda,
  addAgentStack,
  addProjectArchitecture,
  addSystemArchitecture,
  addRuntimeFlow,
  addFeatureOverview,
  addPlanMode,
  addAutomation,
  addChannelKnowledge,
  addMcpSkillToolkit,
  addSecurity,
  addWorkerSubAgent,
  addOpsUi,
  addObservability,
  addScenarios,
  addCompetitorOpenClaw,
  addCompetitorHermesAgentScope,
  addProsCons,
  addRoadmap,
  addSources,
].forEach((fn) => fn());

const pptx = await PresentationFile.exportPptx(presentation);
await pptx.save(FINAL_PPTX);

const snapshot = await presentation.inspect({ kind: "deck,slide,textbox,shape", maxChars: 12000 });
console.log(snapshot.ndjson);
