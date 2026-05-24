# BEHAVIOR-RULE-AB-EVAL — MRD

## 用户痛点 (2026-05-24 Event 123 dogfood 暴露)

**触发场景**：用户在 Main Assistant 跑了若干会话后 → 点 OptReport → AttributionCurator 提出 behavior_rule candidate ("Token 预算控制" 类) → 用户点 approve →

**当前系统行为**：
1. BehaviorRule v1 创建成功 (state=candidate_ready)
2. FlywheelAutoTriggerListener.dispatchBehaviorRuleAutoAb 被调用
3. 该方法注释 "// V5.1 backlog: behavior_rule auto-AB not supported" → 直接 return
4. **没有 A/B 跑、没有 score、没有 promote**
5. FE OptimizationEvents 该 row 卡在 candidate_ready，无 Retry 按钮（Retry 只对 candidate_failed 显示）、无 manual promote 按钮

**用户原话**：
> "你判断下 还需要哪些方向的AB ... 然后 我现在approve的时候 看不到具体的任务状态，也不知道是否能重新执行 behavior_rule 的retry"
> "behavior_rule A/B (你刚遇到的) 这个我现在需要 然后走一下流程"

## 用户需要什么

| 维度 | 用户期望 |
|---|---|
| **能不能跑 A/B** | 能。approve behavior_rule candidate → 自动跑 A/B (with-rule vs without-rule) |
| **跑完能看到分** | 能。OptEvents row 显示 baseline / candidate / delta_pp，跟 prompt A/B 同款 UX |
| **能不能 promote** | 能。即使 V1 没有 auto-promote，要有 manual Promote 按钮 |
| **能不能 retry** | 能。failed 或 stuck 的 row 可以重新触发 A/B |

## 验收点 (MVP)

| AC | 验证方式 |
|---|---|
| AC-1 approve behavior_rule candidate → 异步触发 BehaviorRuleAbEvalService | log 看到 "[BehaviorRuleAb] start runId=..." |
| AC-2 A/B 跑两组 baseline (无 rule) vs candidate (带 rule v1) | t_behavior_rule_ab_run 写入 + status=COMPLETED + ab_scenario_results_json 含 both baseline/candidate per-scenario result（V1 不强求独立 eval_run_id 行；详见 prd.md INV-1）|
| AC-3 dual-criteria score 计算正确 | target_delta_pp / regression_delta_pp 两字段写入 |
| AC-4 OptEvents row 显示 baseline / candidate / delta 跟 prompt A/B 同款 | FE 渲染 |
| AC-5 manual Promote 按钮: 在 dual-criteria 都满足时 enabled | FE 渲染 + POST /api/behavior-rules/versions/{id}/promote 走通 |
| AC-6 Retry 按钮: candidate_ready 长期无 A/B 结果时显示 (跟现有 prompt retry 同款触发) | FE 渲染 + POST .../retry-ab 走通 |
| AC-7 至少一条 EvalScenario 有 rule_trigger_hints 填充 (seed) | DB query: `SELECT COUNT(*) FROM t_eval_scenario WHERE rule_trigger_hints IS NOT NULL AND rule_trigger_hints != '[]'` ≥ 5 |
| AC-8 fallback: filter 后 target subset 为空时, 仍跑全 dataset 作为 regression check | 单元测试覆盖 + log 看到 "[BehaviorRuleAb] target subset empty, fallback to full dataset" |

## 不在 V1 范围

- auto-promote chain (用户明确 V1 manual promote)
- rule_text variant A vs B (用户明确 V1 with vs without)
- behavior_rule attribution candidate 自动建议加什么 hint (V2)
- LLM-as-judge 评 rule 行为差异 (V2+)
