# EVAL-DATASET-LAYER — Eval 数据层重新设计

> 创建：2026-05-23
> 状态：active
> 模式：Full pipeline（schema + 新 entity + Service / Controller / FE 跨栈 + benchmark scenario 种子）
> 关联：解决 OPT-REPORT-V1 + flywheel A/B 跑出 baseline=0% / candidate=0% / delta=0 的"无可比性"问题
> 触发观察：2026-05-23 Event 122 A/B 三次 retry 验证 V108+timeout+race fix 全过，但 baseline 仍 0% — 根因是当前 scenarios 100% 抽自 agent failure session，对 baseline 不公平

## 文档结构

- [mrd.md](mrd.md) — 用户真正想要什么（产品意图 + 当前痛点）
- [prd.md](prd.md) — 产品定义（entity / source_type / dataset / composition policy）
- [tech-design.md](tech-design.md) — 技术方案（V109 migration / EvalDataset entity / Service / FE）
- [benchmark-selection.md](benchmark-selection.md) — 30 个 benchmark scenarios 挑选清单（待 implement 时填）

## r4-r2 scope 扩展（2026-05-24 ratify）

Phase 3 Reviewer 升级 SkillAb 路径 silent-failure 为 blocker，team-lead ratify "本轮补完不能 backlog"。V1 实际范围扩展到 **skill surface BE 全 ready + FE UI V2**：

- ✅ V113 migration (t_skill_ab_run.dataset_version_id) + SkillAbRunEntity 字段 + SkillController body 接受 + Service 6-arg overload + toAbRunMap emit
- ✅ FE 类型 forward-compat 字段保留（V1 BE 不 emit/接 → undefined no-op）
- ❌ FE SkillAbPanel UI **不暴露 user 入口**（仅 scope 注释，等 V2）
- 详见 [prd.md "r4-r2 scope 扩展"段](prd.md#r4-r2-scope-扩展-2026-05-24-ratify)

## 核心决策（已锁，r2 wiki research 之后更新）

1. **EvalScenario 名字保留**（统一 entity 名，跟业界 example/case 对应 — wiki 业界 example/case/sample/DatasetItem 没统一名字，"scenario" 跟 OpenClaw QA Eval 同义）
2. **加 `source_type` 字段**: `benchmark` / `session_derived` / `manual` —— 现有 6 行 retroactive 标 session_derived
3. **加 `purpose` 字段** ★ wiki r2 新加 ★: `baseline_anchor` / `regression` / `ablation` —— 跟 source_type 正交，对齐 SWE-bench regression-aware 思路
4. **新加 EvalDataset + EvalDatasetVersion 实体**（命名 + 版本化 collection，n:n 关联 EvalScenario）
5. **EvalDatasetVersion 加 `composition_hash` SHA256** ★ wiki r2 新加 ★ —— 参考 τ-bench `gt_data_hash`，跨 version diff detection
6. **EvalDatasetVersion.composition_stats 加 `expected_baseline_pass_rate`** ★ wiki r2 新加 ★ —— FE 选 dataset 时显示预估区间
7. **不干掉现有 session_derived scenarios** —— 重定位为 `purpose='regression'`，不是 main baseline
8. **30 benchmark scenarios 种子**：GAIA Lv1 12 + τ-bench 6 + AgentBench OS+DB 6 + SkillForge dogfood 6（详见 benchmark-selection.md）—— wiki 业界推荐分布完全一致
9. **不采用 SWE-bench / WebArena / GSM8K 等**（详见 benchmark-selection.md "❌ 不采用" 段，wiki 直接论断）
10. **Dataset composition policy V1 只警告不强制**（一致 Opik cross-validation 思路；UC-4 真活）

## Plan-stage Reviewer Findings 处置（r4 加，2026-05-24）

### r4 — Full plan-stage adversarial review 完成

3 reviewer (architect + database-reviewer + java-design-reviewer) 全跑完出 r1 report，Judge 仲裁出 **2 BLOCKER + 7 mandatory warning + 4 discretionary**，全部融入 PRD v4 / tech-design v4：

| # | Severity | Finding | 处置 |
|---|---|---|---|
| **B1** | 🔴 BLOCKER | `EvalScenarioEntity.origin` 字段名跟 SessionEntity.origin 同名不同语义 → JPQL/log/FE filter 长期混淆 | 字段名全文 rename `origin` → `source_type` (57 处) |
| **B2** | 🔴 BLOCKER | t_eval_dataset / t_eval_dataset_version `TIMESTAMP` 应为 `TIMESTAMPTZ` (Hibernate Instant 映射 + 项目 convention) | schema 全改 TIMESTAMPTZ |
| **W1** | 🟡 mandatory | EvalDatasetVersionEntity 漏 compositionHash Java 字段 (silent null) | Entity 加 field |
| **W2** | 🟡 mandatory | V109 缺 IF NOT EXISTS idempotency guard | 全部加 IF NOT EXISTS + DO $$ ... $$ block for constraint |
| **W3/W5** | 🟡 mandatory | ON DELETE RESTRICT 文档不全 / V1 实务不可删 | tech-design §1.4 含意 + trap note 完整 |
| **W4** | 🟡 mandatory | publishVersion 空 scenarioIds 行为未定义 | throw IllegalArgumentException |
| **D1** | 🟡 discretionary | expected_baseline_pass_rate 无校正机制 | 加 actualBaselinePassRate 字段，A/B run 完反写 |
| **D2** | 🟡 discretionary | AbEvalPipeline 新旧 overload 无 deprecation | 旧 overload @Deprecated(forRemoval=true) + log.warn |
| **D3** | 🟡 discretionary | runAbTestAgainst 5-arg 膨胀 | 引入 AbEvalRunRequest record param object |
| **D4** | 🟡 discretionary | computeExpectedBaselinePassRate 硬编码无 strategy | 抽 BaselinePassRateHeuristic interface |

Report 路径: `/tmp/review-eval-dataset-layer-{db,java-design}-r1.md` (db + java-design Write 完整 report；architect agent 无 Write tool 改 inline message 交付，关键内容已 capture 进本表)。

### r3 — pre-Write self-check 4 finding（已纳入 r4 一并处置）

| # | Finding | 处置 |
|---|---|---|
| 1 | V109 缺 ALTER agent_id DROP NOT NULL | tech-design §1.1 已加 |
| 2 | category 跟 source_type 重叠 | 实际语义正交，tech-design §1.1 加区分表 |
| 3 | V112 gen_random_uuid() 兼容性 | embedded PG 14 原生支持，不动 |
| 4 | EvalDatasetEntity.agentId type mismatch | known-tech-debt 注释 |

## 业界对照（基于 wiki research 2026-05-23）

参考 [wiki query: eval-dataset-layer-design](/Users/youren/myspace/research-docs/research/agent-harness-wiki/queries/eval-dataset-layer-design.md)：

- ✅ **三层 entity** (EvalDataset / EvalDatasetVersion / EvalScenario) 跟 **Langfuse** `Dataset / DatasetItem / DatasetRunItem` 完全对齐
- ✅ **immutable version snapshot** 一致 Langfuse + Opik + τ-bench `gt_data_hash`
- ✅ **30 benchmark 选型** 跟业界推荐完全一致
- ✅ **composition policy 警告不强制** 一致 Opik cross-validation
- ⚠️ 业界 4 条 enhancement 已全部融入 PRD/tech-design（purpose 字段 / composition_hash / expected_baseline_pass_rate / 不采用理由文档化）
- ❌ 警告：**不要重命名 EvalScenario** —— 业界本来就没统一名字，改名 ROI 太低

## 跟其它需求关系

- **OPT-REPORT-V1**：报告生成不变；本包提升 A/B 跑出来有意义 delta 的能力
- **ANNOTATOR-BEHAVIOR-SIGNALS**：不冲突
- **V6-FIX-* / V108 系列**：本包之上跑，链路依赖已通

## 范围

- ✅ EvalScenario 加字段 + 现有 6 行 retroactive
- ✅ EvalDataset 实体 + n:n 关联 + dataset_version snapshot
- ✅ PromptAbRun 加 `dataset_version_id` 锁 run 版本
- ✅ 30 benchmark scenarios 种子 migration
- ✅ A/B Service 改成读 dataset_version_id 不是 agent_id+split
- ✅ FE：EvalScenarios page 加 source_type filter + Dataset tab + composition health badge
- ❌ **不做**：scenario auto-discovery from external benchmark URL（手动 cherry-pick + seed migration）
- ❌ **不做**：dataset 跨 owner 共享（V1 单 tenant；权限留 V2）
- ❌ **不做**：MULTI-DIM-ATTRIBUTION 拓展（已交付独立）
