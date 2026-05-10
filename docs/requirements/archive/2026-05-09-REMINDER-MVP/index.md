# REMINDER-MVP system-reminder 框架（Phase A）

---
id: REMINDER-MVP
mode: full
status: design-ratified
priority: P2
risk: Full
created: 2026-05-09
updated: 2026-05-09
ratified: 2026-05-09
---

## 摘要

引入 `<system-reminder>` 注入框架，每轮 LLM 请求前自动追加一段"系统现状摘要"到 prompt 动态段，让 LLM 在每轮回答前感知系统状态变化（context 用量 / 最近文件 / memory 陈旧性）。

Phase A：框架地基 + 3 个高价值 source + P9-5 recovery payload 迁移到 `<system-reminder>` 包装（履行 P9-5 D3 决策）。

## 范围（Phase A）

**做**：
- `ReminderBuilder` 框架（per-source enable yaml + 总 budget 5K token + 拼接顺序）
- `ReminderSource` 接口
- 3 个 source：MemoryAge / ContextUsage / FileActivity
- P9-5 `RecoveryPayloadBuilder.build()` 输出改 `<system-reminder>` 包装

**不做（Phase B/C/V2）**：Date Change / Skill enable 变化 / Compact 失败计数 / MCP 状态变化（Phase B）；Lifecycle Hooks 联动 / dashboard 配置页（Phase C）；claude-code user message prepend 模式（Phase D，不一定做）；TodoWrite / Plan-mode / Malware（永不做，cli-only）。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)（D1-D8 已 ratified 2026-05-09）
3. [技术方案](tech-design.md)（含 file:line 接入位置）

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 相关 P9-5（D3 履行目标） | [P9-5 归档](../../archive/2026-05-09-P9-5-post-compact-recovery/index.md) |
| 调研参考 | claude-code v2.1.88（13 source / Turn Count Debounce） |
