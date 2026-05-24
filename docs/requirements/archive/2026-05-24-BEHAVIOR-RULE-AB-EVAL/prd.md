# BEHAVIOR-RULE-AB-EVAL — PRD

## 目标

给 behavior_rule surface（Main Assistant 等 agent 的行为规则版本）补上 A/B 评测 + manual promote，闭合 V4 飞轮在 behavior_rule path 的缺口。

## 5 决策详解

### D1 触发问题：filter + fallback

**问题**：behavior_rule 通常只在特定场景才"触发"（如 "Token 预算" rule 只在长任务/工具结果大时才该 fire）。49 条 dataset 全跑也许只有 5 条真触发该 rule。

**方案**：

1. `t_eval_scenario` 加 JSONB `rule_trigger_hints` 字段，存字符串 array，如 `["uses_bash", "long_tool_output", "multi_loop"]`
2. `t_behavior_rule_version` 加 JSONB `target_trigger_tags` 字段（rule 自己声明它该被哪些 hint scenario 触发；默认从 attribution 信号自动填，也可手 edit）
3. A/B 时：
   - **target subset** = scenarios where `rule_trigger_hints ∩ rule.target_trigger_tags ≠ ∅`
   - **regression subset** = scenarios where `rule_trigger_hints ∩ rule.target_trigger_tags = ∅`
   - 两个 subset 都跑，两个 delta 都算
4. **fallback**：target subset 为 0 → 仍跑 full dataset 当 regression check（rule_text 注入 system prompt 全局生效，regression check 即使 target 没触发也有意义）→ 此时 promotion criteria 弱化为"regression delta ≥ -3pp"

**V1 seed**：mixed dataset 的 49 条 scenarios 中，按任务关键词手填或脚本生成 hints（如 task 含 "use bash" → hint `uses_bash`；含 "search file" → `uses_grep`），至少 ≥5 条非空。

### D2 A/B 形态：with-rule vs without-rule

| run | system prompt 内容 |
|---|---|
| **baseline** | agent 系统提示 + 其它 behavior_rules **不带本条 v1** |
| **candidate** | agent 系统提示 + 其它 behavior_rules **加上本条 v1** |

不是 v1 vs v2 (那是 V2 backlog)，是 with vs without。这种"启用/不启用"对照能直接回答"这条 rule 真有用吗"。

### D3 Injection 复用现有

现有 `BehaviorRuleService.applyTo(systemPrompt, agentId)` 已被 AgentLoopEngine system prompt 拼装路径用。本 A/B 框架在跑 baseline 时**临时禁用本条 rule 的注入**（candidate id 黑名单 / 覆盖入参），candidate 时正常注入。不在 LlmProvider / Tool / 别的 boundary 加新 injection point。

### D4 Promotion criteria

| 子集 | 阈值 |
|---|---|
| target subset (rule 触发题，N ≥ 5) | pass_rate delta ≥ **+10pp** |
| regression subset (rule 不触发题) | pass_rate delta ≥ **-3pp** |
| **若 target subset 为空** (fallback 模式) | 只校 regression delta ≥ -3pp |

dual-criteria 都满足 → promote 按钮 enabled。任一不满足 → disabled + tooltip 解释原因。

### D5 V1 scope = MVP

包含：
- A/B framework（with vs without 两组 run）
- dual-criteria score 计算 + 落 t_behavior_rule_ab_run
- 跟 prompt A/B 同款 UX：OptEvents row 显示 baseline / candidate / delta
- **manual** Promote 按钮（dual-criteria 满足时 enabled）
- Retry 按钮（candidate_ready 长期无结果时显示）

不包含：
- auto-promote chain (依赖 manual 操作，可在 V2 接 `PromptPromotionService` 同款机制)
- rule_text variant A vs B
- LLM-as-judge

## 用例

### UC-1 用户 approve behavior_rule candidate

```
User: /opt-report → AttributionCurator 提 behavior_rule candidate "Token 预算控制"
User: 在 OptimizationEvents 点 [Approve]
System:
  - 创建 BehaviorRule v1 (state=candidate_ready)
  - FlywheelAutoTriggerListener.dispatchBehaviorRuleAutoAb 异步触发
  - BehaviorRuleAbEvalService.runAb(versionId)
    - resolve target subset + regression subset (按 rule_trigger_hints ∩ target_trigger_tags)
    - 启 2 个并行 AbEvalPipeline runs (baseline 禁用本 rule + candidate 启用)
    - 落 t_behavior_rule_ab_run，target_delta_pp / regression_delta_pp 填进 metrics_json
FE OptimizationEvents row:
  - 进度: running A/B → done
  - 显示: baseline 36.7% / candidate 42.9% / target_delta +14pp / regression_delta -1pp
```

### UC-2 用户 manual promote (dual-criteria 满足)

```
User: 点 [Promote v1] 按钮
System:
  - 校 dual-criteria 满足
  - BehaviorRulePromotionService.promoteManual(versionId)
    - mark v1 state=active
    - mark prior active version state=archived
    - emit Optimization Event "behavior_rule.promoted" (manual)
```

### UC-3 用户 retry (candidate_ready 卡住)

```
User: 在卡住的 row 点 [Retry A/B]
System:
  - 重新触发 BehaviorRuleAbEvalService.runAb(versionId)
  - 之前的 ab_run 标 state=superseded (保留历史)
  - 新 ab_run 落库
```

### UC-4 fallback (target subset = 0)

```
candidate rule.target_trigger_tags = ["use_external_api"] (新 hint, dataset 还没填)
target subset filter 结果 = 0 条
System:
  - log warn "[BehaviorRuleAb] target subset empty for versionId=..., fallback to full dataset"
  - target_delta_pp = null
  - regression_subset = 全 49 条
  - regression_delta_pp 算出
  - promotion criteria: 只看 regression_delta ≥ -3pp
```

## 验收点 (回 mrd.md)

见 [mrd.md](mrd.md) AC-1 ~ AC-8.

## 关键不变量

| INV | 描述 |
|---|---|
| INV-1 | **V1 弱化**：t_behavior_rule_ab_run 写入后 status 必 ∈ {PENDING/RUNNING/COMPLETED/FAILED/SUPERSEDED}；baseline_eval_run_id + candidate_eval_run_id 在 V1 阶段保持 nullable（runWithExplicitDefs 直接跑 def 不创建独立 EvalTaskEntity）。V2 接入 EvalTaskEntity 后收紧为 NOT NULL（V115 已留列）|
| INV-2 | baseline run 在 LlmProvider 接收 systemPrompt 时**确实不含本条 rule v1 text** (单测验) |
| INV-3 | target subset + regression subset = full dataset (无重叠无遗漏；目标对集是 `hints ∩ tags ≠ ∅`，其余皆 regression) |
| INV-4 | fallback 模式 (target subset = 0) 下 target_delta_pp 必为 null，regression_delta_pp 必非 null |
| INV-5 | dual-criteria 满足公式：`(target_delta_pp >= 10.0 OR target_delta_pp IS NULL) AND regression_delta_pp >= -3.0` |
| INV-6 | promote 操作幂等：v1 已 active 时再点 Promote 返回 no-op + 200 OK |

## 风险与回滚

| 风险 | 应对 |
|---|---|
| t_eval_scenario 加 rule_trigger_hints 列影响现有 rewrite 路径 | EvalScenario 不走 SessionService.rewriteMessages，不触发 identity-column-on-rewrite invariant；列归类 business field |
| baseline 禁用 rule 的实现污染 production rule 注入路径 | 用入参 disabledRuleIds Set 走 BehaviorRuleService 现有方法 overload，不动 prod default 路径 |
| 并行 2 个 AbEvalPipeline runs 抢 loopExecutor | 复用 Semaphore cap=3 throttle (从 EVAL-DATASET-LAYER V1 r3.1 继承) |
| Auto-trigger v.s. manual 重入 (用户 retry 时 auto 还在跑) | t_behavior_rule_ab_run 加 active_run_id 唯一 (一个 version 同时只有一个 running run)，retry 时 mark 老 run superseded |

## 跟其它需求关系

- 依赖 **EVAL-DATASET-LAYER V1** (已交付): mixed dataset / EvalScenario / AbEvalPipeline parallel batch
- 依赖 **V4 BehaviorRule attribution + version chain** (已交付): rule 的创建、approve、active/archived 状态机
- 不依赖 V2 backlog: 不需要 attribution 自动建议 hints (V1 用户手填 or auto-derive 简单规则)
