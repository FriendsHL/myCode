# SYSTEM-AGENT-TYPING 系统 agent 类型区分 + 观察面板

---
id: SYSTEM-AGENT-TYPING
mode: mid
status: design-draft
priority: P2
risk: Low
created: 2026-05-16
updated: 2026-05-16
---

## 摘要

V1-V5 累计交付了 5 个 system agent（memory-curator / session-annotator / metrics-collector / attribution-curator / user-simulator），跟用户自己创建的对话型 agent（Main Assistant / Design Agent / Research Agent / Session Analyzer / Code Agent）**混在同一张 t_agent 表 + 同一个 dashboard AgentList**。

用户痛点 3 条:
1. **混淆**：AgentList 一眼看不清哪些是"自己用的"哪些是"平台跑的"
2. **危险**：用户能编辑 / 删除 system agent，可能破坏 cron 跑的飞轮链路
3. **看不到**：system agent 跑产生的 session（origin='production' 由 cron 触发 / origin='user_sim' / 等）散落在多个 detail page，没集中观察入口

## 范围

Mid 档，~2-3 工作日:

1. **数据模型加 `agent_type`**: `t_agent.agent_type` enum 'user' / 'system'，V87 migration 默认 'user' + 5 个已知 system agent 显式设 'system'
2. **FE AgentList 默认隐藏 system agent**：加 toggle "Show system agents" (默认 off)；显示时加 visual badge 区分
3. **System agent 编辑保护**：AgentDrawer 检测 agent_type='system' 时，只读关键字段 (name / model_id / system_prompt / tool_ids)，仅允许 enabled toggle + 监控
4. **System agent 不可发起 chat**：Chat page 检测 agent_type='system' 时禁 send button (admin override 可绕过)
5. **集中观察面板**：新建 `/insights/system-agents` 或 admin section 列 5 个 system agent + 各自最近 N 个 session + cron schedule + last run timestamp + 触发 manual run 按钮 (read-only observability)

## 不在范围内

- **不动数据**：不重命名 / 删除已存在 agent，仅加新字段
- **不破 V1-V5 现有路径**：bootstrap (V81/V79/V75/V85) 写入逻辑不动，只在 idempotent update 时补 agent_type='system'
- **不动 t_session_message / chat 路径** (Iron Law 核心 7+1)
- **不重写 AgentList**，只加 filter

## 阅读顺序

1. [MRD](mrd.md) — 痛点详述 + 用户场景
2. [PRD](prd.md) — Phase 1 范围 + 验收点 + UI 草图
3. [技术方案](tech-design.md) — 数据模型 + V87 migration + FE filter + 观察面板

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
