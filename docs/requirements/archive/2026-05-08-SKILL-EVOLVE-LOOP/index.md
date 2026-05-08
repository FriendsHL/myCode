# Skill Self-Evolve Loop

---
id: SKILL-EVOLVE-LOOP
mode: full
status: delivered
priority: P0
risk: Full
created: 2026-05-08
updated: 2026-05-08
delivered: 2026-05-08
---

## 摘要

打通 SkillForge skill 自进化完整闭环：① 生产（cron 自动 extract from session）→ ② 评测（单 skill EVAL endpoint + 周期定时评测 + history 表）→ ③ 优化（SkillEvolution 用 EVAL failures 不再瞎改）→ ④ 自循环（score < 阈值自动 trigger evolve + auto A/B + promote）+ ⑤ 通知（WS push + dashboard 展示历史曲线 / auto-evolve runs）。

**用户原话（2026-05-08）**："今天要把这个闭环搞定 / skill 生产能力已经很久了，但是闭环这个流程还是没有跑通，不能等用户提需求"。

## 现状盘点

| 阶段 | 现状 |
|---|---|
| ① 生产 | ✅ 手写 / marketplace import / SkillDraftService.extractFromRecentSessions（手动）； ❌ 无 cron 自动 |
| ② 评测 | ✅ EVAL-V2 完整 / SkillAbEvalService A/B fork+test； ❌ 无单 skill 直评 / 无定时 / 无 history |
| ③ 优化 | ✅ SkillEvolutionService.createAndTrigger / `POST /skills/{id}/evolve`； ❌ 不传 EVAL failures（瞎改） |
| ④ 自循环 | ❌ 完全缺 |
| ⑤ 通知 | ❌ 无 WS push / 无历史曲线 |

## 阅读顺序

1. [MRD](mrd.md) — 用户原话 + 现状盘点 + 战略意义
2. [PRD](prd.md) — 5 phase 范围 + 验收点
3. [技术方案](tech-design.md) — 架构 / INV / 模块拆分 / 测试策略 / 错峰 cron 安排

## 链接

| 文档 | 链接 |
|---|---|
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
