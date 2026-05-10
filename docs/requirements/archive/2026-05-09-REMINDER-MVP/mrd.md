# REMINDER-MVP MRD

---
id: REMINDER-MVP
status: mrd
source: user
created: 2026-05-09
updated: 2026-05-09
---

## 用户诉求

LLM 每轮回答时不知道系统当前状态变化（context 用了多少 / 刚改过哪些文件 / memory 哪些过时），要么靠 agent 主动调 tool 探查，要么靠用户在 prompt 里手写提示。希望框架自动在每轮请求前注入一段系统现状摘要。

## 背景

- 调研 claude-code v2.1.88 后发现成熟的 `<system-reminder>` 框架（13 source / Turn Count Debounce / per-source enable）
- SkillForge 现有 P9-5 recovery payload 也是"压缩后注入文件摘要"思路，但用 plain user message，**P9-5 D3 ratify 时埋伏笔说"等 system-reminder 框架后统一迁移"**
- SkillForge 已有 3 类高价值数据源现成可用（数据源盘点 2026-05-09）：
  - Memory: `MemoryEntity.lastRecalledAt`
  - Context 用量：`CompactThresholds` + `ContextBreakdownService`（**claude-code 居然没做这个**——SkillForge 独有价值）
  - File activity: P9-5 刚加的 `FileStateCache.snapshot`

## 期望结果

- LLM 每轮请求里 system prompt 末尾（动态段、cache boundary 之后）多一段 `<system-reminder>` 文本
- 文本内容自动按 source 拼接（context 用量 → memory age → file activity → P9-5 recovery）
- 各 source 独立可配置阈值 / enable / disable
- 不破坏现有 user/assistant 消息交替
- 不影响 P13 prompt cache 命中（注入到动态段）

## 约束

- 必须复用 `AgentLoopEngine.promptSuffix` 现有机制（不动 user message stream）
- Turn Count Debounce 频率控制（避免每轮都注同样信息）
- per-source 总 budget ≤ 5K token，超出按顺序截断
- P9-5 recovery 输出需迁移到 `<system-reminder>` 包装（D6 决策）
- 不破坏 tool_use ↔ tool_result 配对

## 未决问题

无（D1-D8 已在 PRD ratify 2026-05-09）。
