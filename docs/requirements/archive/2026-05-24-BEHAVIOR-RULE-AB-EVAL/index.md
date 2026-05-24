# BEHAVIOR-RULE-AB-EVAL — behavior_rule surface A/B 评测框架 V1

> 创建：2026-05-24
> 状态：active
> 模式：Full pipeline（新 schema + 新 service + 跨栈 + 改 FlywheelAutoTriggerListener）
> 触发：用户 2026-05-24 Event 123 dogfood (OptReport → behavior_rule) 暴露
> "behavior_rule auto-AB not supported (V5.1 backlog)" 卡 candidate_ready 无 Retry / 无 promote 入口

## 背景

EVAL-DATASET-LAYER V1 跑通后 Event 123 触发 behavior_rule path:
- approve → BehaviorRule v1 创建 → candidate_ready
- FlywheelAutoTriggerListener.dispatchBehaviorRuleAutoAb **skip** (V5.1 backlog 注释)
- 卡死 candidate_ready 永不 promote，FE 没 Retry 按钮也没 manual promote 入口
- 用户没有 A/B 评测数据支撑该 rule 是改善还是负担

## V1 设计 5 个 ratify 决策 (2026-05-24)

| # | 决策 | 选 |
|---|---|---|
| **D1** 触发问题 | EvalScenario 加 `rule_trigger_hints` JSONB 字段，A/B 时 filter target subset + fallback 跑全 dataset (regression check) | filter+fallback |
| **D2** A/B 形态 | with-rule vs without-rule (baseline = agent 不带 rule，candidate = 带 rule v1) | with vs without |
| **D3** 注入机制 | 复用现有 BehaviorRuleVersion → agent system_prompt 注入路径 (V4 实现)，不写新 injection point | 复用现有 |
| **D4** Promotion criteria | dual-criteria: target subset pass_rate delta ≥ +10pp **且** regression subset delta ≥ -3pp | dual-criteria |
| **D5** V1 scope | MVP: A/B 框架 + dual-criteria score + manual promote 入口；auto-promote chain V2 | MVP first |

## 跟现有飞轮关系

- **prompt A/B** (EVAL-DATASET-LAYER V1 + V108): 完整 auto-promote chain
- **skill A/B** (V4 SkillAbEvalService): 完整 auto-promote chain
- **behavior_rule A/B** (本包 V1): 仅 A/B 框架 + manual promote, **不接 auto-promote chain**

## 文档结构

- [mrd.md](mrd.md) — 用户痛点 + 验收点
- [prd.md](prd.md) — 用例 + V1 范围 + 5 决策详解
- [tech-design.md](tech-design.md) — schema (V114-V116) + entity + service + REST + FE

## 不在范围 (V2+ backlog)

- auto-promote chain (V1 仅 manual promote)
- rule_text variant A vs B (V1 仅 with vs without)
- LLM-as-judge 评 rule 行为差异
- 跨多个 behavior_rule version 的横向对比
