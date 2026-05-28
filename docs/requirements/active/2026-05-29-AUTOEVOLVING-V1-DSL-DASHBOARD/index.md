# AUTOEVOLVING V1 — DSL workflow engine + autoEvolving dashboard

> **创建**：2026-05-29
> **状态**：prd-draft，待 user 拍板启动
> **父需求包**：[AUTOEVOLVING-MASTER](../2026-05-28-AUTOEVOLVING-MASTER/) (V1 子需求)
> **估时**：~4.5 周（4 sprint）

## 30 秒摘要

V1 双核心交付：

1. **DSL workflow engine** — 严格参考 [Claude Code Workflow](../../../../research-docs/research/claude%20code%20源码/08%20Workflow%20工具与编排指南.md)（JS 子集 + 6 原语 + schema 强制 + sandbox）。实现栈：**Rhino**（Mozilla JS engine 纯 Java ~5MB）+ L1 capability sandbox。支持 `.workflow.js` 文件**动态热加载**（不重启服务），agent 可以自己写 workflow。
2. **autoEvolving dashboard `/autoevolving`** — KPI 卡 + 3 信号源面板（production / autoResearch placeholder / memory）+ workflow DAG viz panel（复用现有 FlywheelObservability + reactflow + dagre）+ 异常诊断面板 + 手动触发 workflow 按钮。

## 文档清单

- [`index.md`](index.md)（本文）— V1 入口 + 摘要
- [`prd.md`](prd.md) — 目标 / 非目标 / FR / AC / 决策记录
- [`tech-design.md`](tech-design.md) — Rhino 集成 + 6 原语实现 + 沙箱 L1 + dashboard tech
- [`dsl-syntax.md`](dsl-syntax.md) — DSL 语法参考（你写 workflow 时查这个）

## V1 范围

详见 [`prd.md`](prd.md)。摘要：

- 双核心 = DSL engine + dashboard
- OPT-REPORT 改造为 demo workflow（保留 agent-driven fallback 防 regression）
- 复用：FlywheelRunService / SubAgentRegistry / ChatService / FlywheelObservability / reactflow+dagre / ScheduledTaskExecutor / Hook framework
- **不做**：outer loop（V3 K-4）/ K-1~K-4（V2-V3）/ DSL Phase 2 humanApprove 完整版 / workflow 嵌套 / 3 信号源融合（V3）/ 框架自进化（V5）/ AUTORESEARCH 数据接入（V2，V1 留 placeholder）

## 估时拆分

| Sprint | 工作 | 周 |
|---|---|---|
| **Sprint 1** | Rhino 集成 + L1 capability sandbox（ClassShutter + instruction cap + budget）+ 6 原语 host binding + hello-world workflow + 安全审计 | 1.5 |
| **Sprint 2** | humanApprove（简化版）+ Schema 强制 + journal/resume + ConsolidationLock Java 实现 | 1 |
| **Sprint 3** | OPT-REPORT 改造为 demo workflow（保留 agent-driven fallback）+ workflow DAG viz panel（复用 FlywheelObservability）+ 真活验证 | 1 |
| **Sprint 4** | dashboard `/autoevolving` page + 3 信号源面板 + 异常诊断面板 + KPI 卡 + 手动触发 button + dogfood | 1 |
| **小计** | | **~4.5 周** |

## D 决策（user 2026-05-29 ratify）

| # | 决策 | 选 |
|---|---|---|
| **D1** | DSL 形态 | JS 子集脚本，严格参考 Claude Code Workflow 形态 + 原语 + 哲学 |
| **D2** | DSL 实现栈 | **Rhino**（Mozilla JS engine 纯 Java ~5MB），不是 GraalVM（overkill）/ Java DSL builder（不能动态加载）/ YAML（失去 dynamic 表达力） |
| **D3** | 沙箱保护层级 | **L1 capability-based** = Rhino ClassShutter 禁所有 Java 类 + instruction count cap + 单 workflow timeout 30min + agent call budget 1000；不需 process-level sandbox（CodeSandboxTool）|
| **D4** | dashboard 入口 | `/autoevolving` 新 page（M2 ratify） |
| **D5** | workflow 触发方式 | V1 含手动触发按钮（M5 ratify），cron 自动触发推 V2+ |
| **D6** | OPT-REPORT 改造方式 | 提供 DSL workflow 实现 + **保留 agent-driven fallback**（生产 OPT-REPORT-V1 0 regression）|
| **D7** | autoResearch 数据接入 | V1 不接入（M3 ratify），dashboard 留 placeholder「AUTORESEARCH V1 to ship」 |

## Q 待澄清

| # | Q | 默认提案 | 等 user 拍 |
|---|---|---|---|
| **Q1** | DSL workflow 文件存哪 | `skillforge-server/src/main/resources/workflows/*.workflow.js`（V1 仓库内）→ V3+ 加 DB 存储（dashboard 编辑器） | ⏸️ |
| **Q2** | workflow run 失败时 retry 策略 | 单 workflow 总 timeout 30min；agent() 调用失败重试 1 次；schema 验证失败重试 3 次（同 Claude Code Workflow） | ⏸️ |
| **Q3** | journal/resume 范围 | V1 简化版：完成的 agent() 调用缓存 result，挂掉重跑时跳过；不做 partial state 持久化（V2+ 完整版） | ⏸️ |
| **Q4** | humanApprove 简化版形态 | V1 = 暂停 workflow + 推 WS + dashboard 显 review 卡 + user click approve/reject → resume；不做 UI template / multi-reviewer / timeout reject（V2 完整版） | ⏸️ |
| **Q5** | DSL workflow 默认权限 | V1 admin only 触发；agent 写 workflow 受 Iron Law 人审（先写 review 队列等 user 看，后才注册）；V2 加细粒度 RBAC | ⏸️ |

## 接下来

1. user 拍 Q1-Q5
2. 起 Plan pipeline（Full pipeline 触红灯：跨 server / dashboard / 多模块 + 新 schema + sandbox 设计）
3. Sprint 1 启动
