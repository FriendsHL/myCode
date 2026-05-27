# OUTCOMES-RUBRIC-FOUNDATION — 借鉴 Anthropic Managed Agents Outcomes（rubric + grader 循环）

> 创建：2026-05-26
> 状态：**backlog**（用户 5-26 明确把 Dreaming 跟 Outcomes 拆开；Dreaming 进 active 优先 ship；Outcomes 留 backlog，等 [`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../../active/2026-05-26-DREAMING-MEMORY-EXTENSION/) V1 ship 后用户拍是否升 active）
> 模式：Full pipeline 候选（V1 部分可 Mid；V2 触红灯 `AgentLoopEngine`）
> 触发：跟姊妹包 DREAMING-MEMORY-EXTENSION 同一次 wiki ingest（5-26 [`anthropic-managed-agents.md`](../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) 700 行 + claude-code-guide Outcomes deep dive 16 hotspot），用户明确拆分

## 为什么放 backlog 不放 active

- 用户 5-26 原话："你能给我一个 dreaming 的方案吗？只有 dreaming 的。尽量把 Dreaming 和 Outcomes 这个需求分开"
- Dreaming 触碰 Memory 子系统、Outcomes 触碰 Eval / `AgentLoopEngine` 子系统，**独立 ship 风险隔离**
- Dreaming 在 active 队列优先 ship；Outcomes 不抢队列，等 active 那条进展后用户单独拍

## V1 / V2 切分草案（开 active 时再 ratify）

### V1 候选（基础设施 + Grader 隔离，**不触 AgentLoopEngine**）

| 子项 | 内容 | 工作量 |
|---|---|---|
| F1 | `t_rubric` entity（rubric_id / version / scope ∈ {global, agent, task} / scope_ref_id / content ≤4096 char / created_by / created_at / archived_at）+ Flyway migration | XS |
| F2 | `RubricService` + `RubricController` REST API（POST /rubrics / GET /rubrics/:id/versions / GET /rubrics/:id?version=N / DELETE /rubrics/:id soft delete）| S |
| F3 | **Audit-1**: grep + 调用链 verify SkillForge eval pipeline 的 judge LLM 是否真独立 context window（R-AMA-AUDIT-1）— 三种 verdict ✅/⚠️/❌ | XS（15 分钟）|
| G1 | 新 `LlmJudgeService` 用独立 `LlmProvider` Bean `@Qualifier("judgeLlmProvider")` | M |
| G2 | `JudgePromptBuilder`（rubric + artifact + description only，**排除** main agent tool_use history / reasoning trace）| S |
| G3 | `LlmJudgeContextIsolationTest` IT（断言 grader 输入字段限定）| S |

### V2 候选（Outcome Engine 触红灯）

| 子项 | 内容 | 工作量 | 红灯 |
|---|---|---|---|
| O1 | `OutcomeService` 核心 + 5-state `OutcomeResult` enum（satisfied / needs_revision / max_iterations_reached / failed / interrupted）| M | — |
| O2 | `t_outcome` + `t_outcome_evaluation` entity + Flyway migration | S | — |
| O3 | `AgentLoopEngine` 第 5 轴 exit `OUTCOME_SATISFIED` + `ExitReason` enum | L | **是** — 核心引擎 |
| O4 | `LoopContext` 扩 outcome 字段 + `injectGapFeedback()` | S | — |
| O5 | `SessionEvent` enum 加 `USER_DEFINE_OUTCOME` + `OUTCOME_EVALUATION_START/_ONGOING/_END` | XS | — |
| O6 | `OutcomeGapFeedback` model + per-criterion 解析 | M | — |
| N1 | WebSocket relay outcome events | S | — |
| N2 | Files API rubric `file_id` 上传 hook（`files-api-2025-04-14` beta header）| S | — |
| N3 | 前端 `OutcomePanel.tsx`（iteration counter + per-criterion gap + result status）| M | — |

## 调研来源

- wiki [`harness/anthropic-managed-agents.md`](../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) — **关 Outcomes 的 R-AMA-OUT-1~5 + AUDIT-1 共 6 条**
- claude-code-guide Outcomes deep dive（2026-05-26 晚）— 16 hotspot 完整草图（API 协议 schema + 5-state 转换矩阵 + grader 输入边界 + Files API rubric 复用 + perf claim 真实性评估）

## 关 Outcomes 的 wiki R-AMA-* 建议

| 编号 | 优先级 | 内容 | V1/V2 候选 |
|---|---|---|---|
| R-AMA-OUT-1 | P1 | `AgentLoopEngine` 加 `user.define_outcome` 等价 event（第 5 轴 exit） | V2 |
| R-AMA-OUT-2 | P1 | 新 entity `t_rubric` 跨 session/surface 复用 | V1（F1+F2） |
| R-AMA-OUT-3 | P2 | `OutcomeEvaluation` 5-state enum + 持久化到 session events 流 | V2 |
| R-AMA-OUT-4 | P2 | verify SkillForge judge LLM 是否真独立 context — Anthropic 显式 guarantee | V1（F3 audit） |
| R-AMA-OUT-5 | P3 | Outcome chain in sequence（`define_outcome` 后再 `define_outcome` 接力）| 不做 |
| R-AMA-AUDIT-1 | P2 | judge LLM context isolation grep audit | V1（F3）|

## 升 active 触发条件

满足以下**任一**：

1. [`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../../active/2026-05-26-DREAMING-MEMORY-EXTENSION/) V1 ship 后用户主动拍 "Outcomes 现在做"
2. 业务真有 target-driven autonomous agent 场景需求（如：用户给 task 时希望 engine 强制按 rubric 评不满意就再来一轮）
3. dogfood 发现现有 eval pipeline 的 judge LLM 真的不独立 context（silent quality bug）— 此时优先做 F3 audit + G1-G3 grader 隔离（不依赖 V2 outcome engine）

## 升 active 时要做的事

1. 把本 `index.md` 改 `状态: prd-draft`
2. 把目录 mv 到 `requirements/active/<升 active 日期>-OUTCOMES-RUBRIC-FOUNDATION/`
3. 补 4 件套 `mrd.md` / `prd.md` / `tech-design.md`（参照姊妹包 DREAMING-MEMORY-EXTENSION 风格）
4. 更新 `docs/todo.md` + `docs/README.md`

## 关联

- 姊妹包：[`active/2026-05-26-DREAMING-MEMORY-EXTENSION/`](../../active/2026-05-26-DREAMING-MEMORY-EXTENSION/) — 同次研究产出的 Dreaming 部分
- wiki R-AMA-OUT-1/2/3/4/5 + R-AMA-AUDIT-1
- claude-code-guide Outcomes deep dive 完整 16 hotspot 表（在 git history 2026-05-26 commit 之前的草稿 `MANAGED-AGENTS-UPTAKE/tech-design.md` Phase 4-5 章节，本 placeholder 升 active 时挪过来）
