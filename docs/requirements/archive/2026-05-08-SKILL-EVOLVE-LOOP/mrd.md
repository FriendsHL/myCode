# SKILL-EVOLVE-LOOP — MRD

---
id: SKILL-EVOLVE-LOOP
status: ratified
created: 2026-05-08
---

## 用户原话（2026-05-08）

> "skill 生产这个能力已经很久了，但是闭环这个流程还是没有跑通，不能等用户提需求"
>
> "今天要把这个闭环搞定。你来安排吧。"

## 用户意图

SkillForge 作为 AI agent 平台的核心战略：**skill 自进化闭环**。生产能力已较完善（手写 / marketplace / session 抽取），但**优化端瞎改 + 评测端没自动化 + 闭环没串起来**——skill 一旦创建后，质量只会因外部因素衰减（用户 prompt 变化 / 模型升级 / 用法演化），但 SkillForge 没有自动机制识别衰减并改进。

## 现状盘点

| 阶段 | 现有 | 缺口 |
|---|---|---|
| ① **生产** | 手写 SKILL.md / marketplace import / SkillDraftService.extractFromRecentSessions（手动）| 无 cron 自动 — 用户 5/8 决定砍 single-session 入口，加凌晨 cron |
| ② **评测** | EVAL-V2 M0-M6 全闭环 / SkillAbEvalService.runAbTest / `POST /skills/{id}/abtest` | 无单 skill 直评（必须 fork 才能 test）/ 无定时评测 / 无 history 表 |
| ③ **优化** | SkillEvolutionService.createAndTrigger / `POST /skills/{id}/evolve` / 自动 fork+improve+A/B | **不传 EVAL failures**（callLlmForImprovement L206-225 只传 usageCount/successCount/successRate + 当前 SKILL.md 喂 LLM 瞎改）|
| ④ **自循环** | — | **完全缺**：score 低 → 自动 evolve → auto A/B → promote 没串 |
| ⑤ **通知** | — | 无 WS push / 无 dashboard 历史曲线 / 无 auto-evolve runs 段 |

## 用户场景

### 场景 1：skill 自动迭代不需要人工
用户配了一个 "DebugTraceSkill"，3 周后某 evaluation：
- **当前**：得分跌到 50，用户不知道，skill 持续被 agent 用，导致 agent 表现退化。即使用户偶尔 review skill 也想不到怎么改
- **闭环后**：
  1. 周一凌晨 4 点定时评测发现 DebugTraceSkill 得分 50（< 阈值 60）→ history 表落库
  2. 周二凌晨 5 点 self-improve loop 触发 → SkillEvolution 取最近 5 个失败 scenario 喂 LLM 出新版 → fork → 自动 A/B
  3. A/B 通过（candidate 75 > baseline 50 + 15pp）→ promote v2 → WS 推送 "Skill DebugTraceSkill auto-upgraded to v2"
  4. dashboard skill 详情看到历史曲线（50 → 75）+ 优化原因 + diff

### 场景 2：dashboard 监控 skill 健康度
用户进 dashboard → SkillList → 看每个 skill 最近 EVAL 得分 + 趋势 sparkline → 一眼看到哪些 skill 在退化 → 主动手动 evolve（如果不想等周二自动）。

## 战略意义

不闭环 = 半截子产品。SkillForge 跟其他 AI agent 平台的核心差异化是 **skill 工程化**：
- 不仅生产（多渠道）
- 还能评测（多维度）
- 还能优化（基于真实数据）
- 还能自动迭代（不依赖人工触发）

竞品（Claude Code / Cursor / Cline 等）目前只到生产端，评测+优化端基本空白。SkillForge 完成本闭环 = 真正的 differentiator。

## 范围边界

**包括（V1）**：
- ① 凌晨 3 点 cron 自动 extract from session
- ② 单 skill EVAL endpoint + 周一凌晨 4 点定时评测 + `t_skill_eval_history` 表
- ③ SkillEvolution 改造取 EVAL failures 喂 LLM
- ④ 周二凌晨 5 点 SkillSelfImproveLoop（score 低自动 trigger evolve + 监听 A/B 结果 + auto promote）
- ⑤ WS push `skill_auto_upgraded` + dashboard 历史曲线 + auto-evolve runs 段

**不包括（V2/V3）**：
- 退役机制（usageCount 低 + score 持续低 → archive）
- 多 candidate 并行 evolve（A/B/C 同时跑）
- 用户 review feedback influence next iteration
- 跨 skill 能力 graph
- 失败超过 N 次的 skill 提示用户 manual review

## 不确定 / 后续评估

- 周一凌晨评测全 skill 时 LLM 调用峰值（dataset 增长后 → V2 加 EVAL-PARALLEL-SCENARIO 并发）
- 自动 evolve 的 PASS 阈值默认 15pp（来自 SkillAbEvalService.PROMOTION_DELTA_THRESHOLD_PP），可能需要 dogfooding 后调
- 通知噪音（每次 auto-upgrade 都推 WS 可能烦）→ 视实际频率决定是否聚合
