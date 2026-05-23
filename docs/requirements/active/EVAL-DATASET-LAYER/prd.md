# PRD — EVAL-DATASET-LAYER

## 目标

让 SkillForge 飞轮 A/B 评测拿到**有意义的 delta 信号**：通过引入 benchmark-anchored baseline + dataset 实体 + 版本化 + composition policy 三层重构，使 baseline pass rate 从 0% 提升到 30%+，让 candidate 有真实的 "改善 / 倒退" 判定空间。

## 关键概念定义（术语 canonical）

### 1. EvalScenario（保留名字）

**一个**可独立执行的 evaluation 测试用例。SkillForge 的"原子测试单位"，对应业界 Langfuse `DatasetItem` / OpenAI evals `sample` / Promptfoo `test case` / OpenClaw `qa-scenario`（业界没统一名字，"scenario" 跟 OpenClaw QA Eval 同义）。

包含：
- `task` (string) — agent 收到的输入 prompt
- `oracle_type` + `oracle_expected` — 判分标准
- `setup` (optional) — fixture 文件
- `maxLoops` — agent loop 上限
- `performance_threshold_ms` — 性能阈值
- **`source_type` ★ 新加 ★** — 来源分类：`benchmark` / `session_derived` / `manual`
- **`source_ref` ★ 新加 ★** — 具体来源标识（如 `gaia/lv1#001` / `session:5f3f1923` / `manual:user-1`）
- **`purpose` ★ wiki r2 新加 ★** — 用途分类：`baseline_anchor` / `regression` / `ablation`。跟 `source_type` 正交（一条 session_derived scenario 可同时 `purpose=regression`；一条 benchmark 可 `purpose=baseline_anchor`）。对齐 SWE-bench regression-aware 思路（F2P / FAIL_TO_FAIL / PASS_TO_PASS / PASS_TO_FAIL）—— 见 [wiki query: eval-dataset-layer-design](/Users/youren/myspace/research-docs/research/agent-harness-wiki/queries/eval-dataset-layer-design.md)

### 2. EvalDataset（★ 新加实体 ★）

**一组** named + versioned EvalScenario 集合。

包含：
- `id` (UUID), `name` (e.g., "main-assistant-baseline-v1"), `description`
- `owner_id` (V1 都是 1，V2 多租户预留)
- `agent_id` (optional, null = 通用集 / 跨 agent 共享)
- `tags` (e.g., `["gaia", "lv1", "baseline"]`)
- `is_public` (V1 都是 false)

### 3. EvalDatasetVersion（★ 新加实体 ★）

EvalDataset 的**不可变 snapshot**。A/B run / EvalTask 锁某个 version 跑，保证跨 run 可比性。

包含：
- `id` (UUID), `dataset_id`, `version_number` (1, 2, 3...)
- `scenario_ids` (List<UUID>, snapshot at version creation time)
- `composition_stats` (jsonb)：source_type 分布 + 难度分布 + **`expected_baseline_pass_rate`**（★ wiki r2 新加 ★ —— 比如 dataset 100% benchmark 预估 30-50%，100% session_derived 预估 ≤10%，FE 选 dataset 时显示预估区间）
- **`composition_hash` (VARCHAR 64) ★ wiki r2 新加 ★** —— SHA256 of sorted `scenario_ids`，参考 τ-bench `gt_data_hash` ([wiki query line 285](/Users/youren/myspace/research-docs/research/agent-harness-wiki/queries/eval-dataset-layer-design.md))。用途：跨 version diff detection + A/B run 启动时 verify 数据集完整匹配
- `created_at`, `created_by`

EvalScenario 跟 EvalDatasetVersion 是 n:n via `t_eval_dataset_version_scenario` 关联表（不是 hard FK on scenario，scenario 删了 version 也保留 historical ref）。

### 业界对齐说明（wiki r2 加）

| 三层 | SkillForge | Langfuse 对应 | OpenClaw QA Eval 对应 |
|---|---|---|---|
| Collection | `EvalDataset` | `Dataset` | YAML pack |
| Version snapshot | `EvalDatasetVersion` | （隐式，append-only）| —— |
| Item | `EvalScenario` | `DatasetItem` | `qa-scenario` |
| Run | `PromptAbRun` | `DatasetRunItem` | run record |

SkillForge 三层结构跟 Langfuse 完全对齐，业界事实最完整的命名标准。

### 4. Origin 三类

| source_type | 来源 | 用途 | 典型 source_ref |
|---|---|---|---|
| `benchmark` | 公开 benchmark（GAIA / τ-bench / AgentBench / etc.）| **主 baseline anchor** — 给 agent 公平的能力基线 | `gaia/lv1#001`, `tau-bench/airline#5`, `agentbench/os#42` |
| `session_derived` | 抽取自 agent 历史 session（尤其 failure session）| **回归测试** — 防 candidate 在历史踩过的坑上再挂 | `session:5f3f1923-...` |
| `manual` | 用户/开发者手工写 | **dogfood 场景** — 平台特定 use case | `manual:user-1/skillforge-dashboard-tab-redesign` |

## 用例

### UC-1：跑 A/B baseline 公平评测

```
作为 operator
我想 给 agent 3 跑 candidate A/B
让 baseline 用一组 benchmark-anchored scenarios（公认 baseline 题）
得到 baseline 30%+ pass rate
然后看 candidate 能否做到 50%+ → 真有改善的 promote
```

### UC-2：组建命名 dataset

```
作为 operator
我想 把 "GAIA Lv1 10 题 + τ-bench 5 题 + SkillForge dogfood 5 题" 凑成
名为 "main-assistant-baseline-v1" 的 dataset
然后所有 Main Assistant 的 A/B 都跑这一组（直到我升 v2）
```

### UC-3：dataset 版本化

```
作为 operator
我加了 3 个新 benchmark scenarios 进 "main-assistant-baseline" dataset
我想 这变成 v2，v1 仍然存在
之前所有跑 v1 的 A/B run 还能跟新跑 v1 的对比（cross-version 比较）
新启动的 A/B 默认跑 v2
```

### UC-4：composition policy 防 bad dataset

```
作为 operator
我建了一个 dataset 全部是 session_derived (failure 题)
当我尝试用这个 dataset 跑 A/B 时
系统警告 "dataset 100% session_derived，baseline 大概率 0% → 不推荐做 A/B 评测"
鼓励混入 ≥40% benchmark source_type
```

### UC-5：source_type filter

```
作为 operator 在 EvalScenarios 页
我想 按 source_type tab 切换看 (Benchmark / Session-derived / Manual)
快速找到"agent 3 历史失败 case"做手动 inspection
```

## 范围

### V1（本包，r4 加 11 项 reviewer fix）

- ✅ **★ r3 BLOCKER fix ★ V109 加 `ALTER t_eval_scenario ALTER COLUMN agent_id DROP NOT NULL`** —— 让 benchmark source_type scenarios agent_id=null 合法
- ✅ **★ r4 B1 BLOCKER fix ★** 字段名 `origin` → `source_type` (跟 SessionEntity.origin 解 collision，跟同表 source_ref 形成 type-instance 配对)
- ✅ **★ r4 B2 BLOCKER fix ★** t_eval_dataset / t_eval_dataset_version `TIMESTAMP` → `TIMESTAMPTZ` (Hibernate Instant 映射 + 项目 convention)
- ✅ **★ r4 W1 fix ★** EvalDatasetVersionEntity 加 `compositionHash` Java 字段
- ✅ **★ r4 W2 fix ★** V109 schema 全部 `IF NOT EXISTS` guard (idempotent)
- ✅ **★ r4 W3/W5 fix ★** ON DELETE RESTRICT 含意 + V1 不可删 trap 文档化 (tech-design §1.4)
- ✅ **★ r4 W4 fix ★** publishVersion 空 scenarioIds → throw IllegalArgumentException
- ✅ **★ r4 D1 fix ★** EvalDatasetVersionEntity 加 `actualBaselinePassRate` (A/B run 完反写真值)
- ✅ **★ r4 D2 fix ★** AbEvalPipeline 旧 overload `@Deprecated(forRemoval=true)` + log.warn 迁移路径
- ✅ **★ r4 D3 fix ★** runAbTestAgainst 引入 `AbEvalRunRequest` record param object
- ✅ **★ r4 D4 fix ★** computeExpectedBaselinePassRate 抽 `BaselinePassRateHeuristic` interface
- ✅ EvalScenario 加 `source_type` + `source_ref` + **`purpose`** ★ wiki r2 ★ 字段
- ✅ 现有 6 个 scenarios retroactive `source_type='session_derived'` + `purpose='regression'`
- ✅ **★ r3 WARNING 处置 ★** `category`（开放 user-tag）跟 `source_type`（封闭 enum）语义并存不冲突，tech-design §1.1 已明确文档
- ✅ EvalDataset + EvalDatasetVersion 实体 + 关联表 + **`composition_hash` SHA256** ★ wiki r2 ★ + **`composition_stats.expected_baseline_pass_rate`** ★ wiki r2 ★
- ✅ 30 benchmark scenarios 种子 migration（见 benchmark-selection.md）
- ✅ 至少 3 个 named dataset 种子：
  - `main-assistant-baseline-v1` (30 benchmark + 0 session_derived)
  - `main-assistant-regression-v1` (现有 6 session_derived)
  - `main-assistant-mixed-v1` (30 benchmark + 6 session_derived)
- ✅ PromptAbRun 加 `dataset_version_id` 列（nullable for backward compat，新 run 必填）
- ✅ A/B Service：`runAbTestAgainst` 加 `datasetVersionId` 参数 path（保留旧 path）
- ✅ FE EvalScenarios page：source_type filter + dataset tab + composition stats badge
- ✅ AbEvalPipeline 读 dataset_version_id 拿 scenarios，不是按 agent_id+split

### V2+（不在本包）

- ❌ Auto-import benchmark from URL
- ❌ Cross-tenant dataset sharing
- ❌ Dataset diff UI（v1 vs v2 改了什么）
- ❌ scenario 难度自动估计 / 自动平衡
- ❌ skill / behavior_rule surface 的 dataset (本包 V1 只 prompt surface)

## 设计决策

### D1：source_type 字段维度化，不再造并列 entity

**选**：单一 EvalScenario entity + `source_type` 字段
**否决**：Scenario / DataCase 两 entity
**理由**：schema 完全一样，分两 entity 只是 source_type 字段的等价 sealed type，引入更多 N:N + 接口分歧（如 Scenario vs DataCase 各自 Repository / Controller），ROI 太低

### D2：EvalDataset 跟 Scenario 是 n:n，不是 1:n

**选**：一个 scenario 可属多个 dataset
**理由**：同一个 GAIA Lv1 题应该可同时在 `main-assistant-baseline-v1` + `code-agent-baseline-v1` 里出现

### D3：EvalDatasetVersion 是不可变 snapshot

**选**：版本提交后 scenario_ids 集合冻结
**理由**：A/B run 锁版本号，未来再跑同 version 拿到的 scenarios 完全一样，跨 run 可比

### D4：现有 6 个 scenarios retroactive `source_type='session_derived'`，不干掉

**选**：保留
**理由**：MRD 验收点 1 明确要求；它们是真实回归 ground truth，干掉等于丢历史

### D5：composition policy V1 不强制，只警告

**选**：FE / Service warning 提示，不阻塞
**理由**：MVP 不限死 user behavior，operator 想跑 100% failure 也允许（debug 用），但默认警告

### D6：30 benchmark scenarios 选什么

详见 [benchmark-selection.md](benchmark-selection.md)。挑选原则：

- 覆盖 Main Assistant / Code Agent / Research Agent / Session Analyzer / Design Agent 5 个主要 user agent
- 难度分布：30% Lv1（简单, baseline ≥50% pass）+ 50% Lv2（中等）+ 20% Lv3（高难, ≤20% pass）
- 来源分布：GAIA Lv1 12 + τ-bench 6 + AgentBench OS/DB 6 + SkillForge dogfood 6
- 每题 oracle_expected 必须明确可判分（不要主观 "好不好" 的题）

## 验收点（继承 MRD + 加细节）

1. ✅ V109 migration 应用，`t_eval_scenario.source_type` NOT NULL，现有 6 行全 `session_derived`
2. ✅ ≥30 benchmark scenarios 在 t_eval_scenario，全 `source_type='benchmark'` + 含 `source_ref`
3. ✅ 3 named dataset 种子：baseline-v1 / regression-v1 / mixed-v1，每个有 v1 version
4. ✅ Agent 3 跑一次 A/B 用 `main-assistant-baseline-v1` v1：baseline pass rate ≥ 30%
5. ✅ FE EvalScenarios page source_type tab + dataset selector / composition badge 可见可用
6. ✅ PromptAbRun 行有 dataset_version_id 填写（attribution path）
7. ✅ tsc + mvn test 全绿 + Iron Law 核心文件 0 diff
8. ✅ Phase Final 真活 curl 验过 GET /api/eval/datasets + GET /api/eval/scenarios?source_type=benchmark

## 风险

| 风险 | 缓解 |
|---|---|
| 30 benchmark scenarios 挑选不准 / oracle 设计差 | 先挑 10 试跑 → 看 baseline pass rate 分布 → 调 → 再扩到 30 |
| 现有 6 个 scenarios retroactive 出错 | migration 内 idempotent UPDATE + 加 IT 测试 |
| AbEvalPipeline 改读 dataset_version_id 破坏旧 caller | 保留旧 (agent_id+split) path，新加 datasetVersionId path 并列 |
| Iron Law 触碰 | 改动集中在 t_eval_scenario / 新 entity / 新 Service / 新 FE page，不动核心 7+1 |
