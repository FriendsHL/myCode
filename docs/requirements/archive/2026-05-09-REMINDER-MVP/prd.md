# REMINDER-MVP PRD

---
id: REMINDER-MVP
status: prd-ratified
owner: youren
priority: P2
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-09
updated: 2026-05-09
ratified: 2026-05-09
---

> **Ratify 记录（2026-05-09）**：用户基于 claude-code v2.1.88 调研 + SkillForge 数据源盘点，确认 D1-D8 全部按推荐执行，进 Full Pipeline 实施。

## 摘要

引入 `<system-reminder>` 注入框架。每轮 LLM 请求前自动 append 一段系统现状摘要到 prompt 动态段，让 LLM 在回答前感知 context 用量 / memory 陈旧性 / 最近文件等系统状态。Phase A 含框架 + 3 source + P9-5 迁移。

## 目标

- 框架核心：`ReminderBuilder` + `ReminderSource` 接口 + Turn Count Debounce 频率控制 + per-source enable/disable yaml + 总 budget 截断
- Phase A 3 source：Memory age / Context 用量 / File activity
- P9-5 `RecoveryPayloadBuilder.build()` 输出改 `<system-reminder>` 包装（履行 P9-5 D3）
- 注入到 `AgentLoopEngine.promptSuffix` 动态段（不动 user message stream，不破 P13 prompt cache）

## 非目标

- **不**做 user message prepend 模式（Phase D 候选，不一定做）
- **不**做 Date Change / Skill enable 变化 / Compact 失败计数 / MCP 状态变化（Phase B）
- **不**做 Lifecycle Hooks 联动 / dashboard /reminders 页面（Phase C）
- **不**做 TodoWrite / Plan-mode / Malware warning（cli-only，永不做）
- **不**改 LLM provider 拼装层（仅改 system prompt 内容，request body 结构不动）

## 决策清单（已 ratified 2026-05-09）

| # | 决策 | Ratified 答案 | 理由 |
|---|---|---|---|
| **D1** | 需求 ID | `REMINDER-MVP` | 直白，不接 P9 系列 |
| **D2** | 注入挂载点 | 复用 `AgentLoopEngine.promptSuffix` | 现有机制；不破 user-assistant 交替；不影响 P13 stable cache |
| **D3** | 频率控制算法 | Turn Count Debounce（claude-code 模式，单门槛 `turnsSince(lastEmitted) >= sourceInterval`，per-source 自管理） | 13 source 验证过；per-source 算 debounce 让 ContextUsage 高频警告 vs Memory 低频提醒可分别配置 |
| **D4** | per-source yaml 结构 | `skillforge.reminder.<source-name>.{enabled, interval-turns, threshold-*}` | 比列表形式更可扩展（每 source 自带阈值字段） |
| **D5** | 各 source 默认配置 | Memory: `enabled=true / interval-turns=5 / stale-days-threshold=7` ；Context: `enabled=true / interval-turns=1 / pct-threshold=70` ；File: `enabled=true / interval-turns=5 / max-files=5 / min-age-seconds=30` | Context 高频（动态信号变化快）/ Memory + File 低频（变化慢） |
| **D6** | P9-5 recovery 迁移策略 | 改 `RecoveryPayloadBuilder.build()` 输出，外层包 `<system-reminder>...</system-reminder>` 标签；CompactionService 注入逻辑保持不变 | 最小改动，trigger 逻辑（4 路径汇聚）保持；履行 P9-5 D3 |
| **D7** | 多 source 顺序 + budget | 固定顺序 `[ContextUsage, MemoryAge, FileActivity, P9-5 recovery]` + 总 budget 5K token，按顺序累计超 budget 后截断 | Context 警告最重要放前面 |
| **D8** | dedup 机制 | Turn Count Debounce 已自然 dedup，**不加** hash dedup | Phase A 避免过度设计；Phase B 看实际再加 |

## 功能需求

- LLM 每轮请求前，`AgentLoopEngine.runInternal` 拼装 LlmRequest 之前调用 `ReminderBuilder.build(loopCtx)` 得到 reminder 文本
- 文本 append 到 `promptSuffix` StringBuilder（cache boundary 之后的动态段）
- 每个 source 独立 `shouldEmit + emit` 接口；ReminderBuilder 按 D7 顺序遍历 source、累计 token、超 budget 后续 source 跳过
- 各 source 内部用 LoopContext 持有 `lastEmittedAtTurn` 状态（per-session 自动清理）
- 全局 `skillforge.reminder.enabled: false` 时整套 framework 跳过

## 验收标准

- [ ] Phase A 3 source 各自 shouldEmit / emit / Turn Count Debounce 单测通过
- [ ] ReminderBuilder 拼接顺序 + budget 截断 + per-source disable 单测通过
- [ ] AgentLoopEngine.runInternal 集成测试：LLM 请求 system prompt 含 `<system-reminder>` 段
- [ ] 4 种 full compact 路径（B2 / Preemptive / Post-overflow / SessionMemory）后 P9-5 recovery payload 是 `<system-reminder>` 包装的（既有 P9-5 测试更新断言）
- [ ] `skillforge.reminder.enabled=false` 时 promptSuffix 无 reminder 段（feature flag 兜底）
- [ ] `mvn test` 全绿（baseline 1166 + 新增预计 ~25 个）
- [ ] 不破 P13 prompt cache 测试（reminder 在动态段，stable cache hash 不变）

## 验证预期

- 后端 unit + integration tests
- Phase Final 主会话亲跑 mvn test
- e2e（用户做）：mvn install + 重启 server + chat 中观察连续几轮 reminder 出现频率
