# AUTORESEARCH-OPTIMIZATION — autoResearch 自动调研 arm（**AUTOEVOLVING V2 子需求**）

> ⚠️ **2026-05-29 重定位**：本包从「独立 V1 Phase 1」改为「[AUTOEVOLVING-MASTER](../2026-05-28-AUTOEVOLVING-MASTER/) **V2 (a) 子需求** = 外部信号源」。原 PRD 内容（5 D 决策 + FR + AC + Phase 1-4 路线）仍有效，**但启动顺序排在 AUTOEVOLVING V1 (DSL + dashboard) 之后**。V1 dashboard 留 autoResearch panel placeholder「AUTORESEARCH V1 to ship」，本包 ship 后接入数据。

> 💡 **30 秒摘要**：V2 = autoResearch arm MVP（**2 周可上**，外部信号源独立 ROI 高）。每周自动扫 arxiv + GitHub trending → LLM 2-stage 提取（idea + SkillForge gap）→ **Iron Law 人审** → 自动建 backlog item。**不接 14-stage**（V3 才接），不动现有 surface。**关键论据**：业内 11 系统全场无人审 gate，**TMLR 2026 综述把 "Safe autonomous evolution" 列为 ⭐⭐⭐ 开放问题** —— SkillForge Iron Law 正是这个问题的答案（business 决策意外对齐学术 SOTA 共识）。本包完成后为 V3-V4（K-1~K-4 outer loop + falsification + SkillsBench 公开打榜）铺底。

> 🧭 **角色导航**：
> - 👤 **你要 ratify** → §"5 个 ratify 决策" + §"5 个 Q 澄清"（每个 D 我都给了默认提案 + 理由 + 反例）
> - 🎓 **你想理解 why**（面试 / 学习视角） → [mrd.md](mrd.md)（8 轮对话 + 11 系统对照 + 4 痛点）+ wiki [karpathy-autoresearch-thinking](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md) + [iterative-skill-optimization](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md)
> - 🛠 **你要开工** → [tech-design.md](tech-design.md) + 本文 "核心交付" Sprint 1-4 拆分
> - ⏱ **你只有 1 分钟** → 上面 "💡 30 秒摘要" + 下方 "5 ratify 决策" 一列

> 创建：2026-05-28
> 状态：**prd-draft**（PRD 已草拟，等用户 ratify D1-D5 + 回答 Q1-Q5 后开 Plan pipeline）
> 模式：Full pipeline（触红灯：跨 server / dashboard / observability 模块 + 新增 1 表 + scheduled job + WebSearch 集成 + 长 brief）
> 触发：2026-05-28 用户在 research-docs 多轮对话中提出"把 Karpathy `autoresearch` 思想（630 行原型）落到 SkillForge skill 进化"，并明确：(a) 当前飞轮一次性跑到 promoted 即止，缺 outer loop；(b) **外部研究信号源缺失**，autoResearch 应该是飞轮第 2 个信号源（不是单独功能）；(c) 业内 SkillOpt / AHE / EmbodiSkill / MemSkill / EvoSkill / SkillsBench 等 11 系统全是 Karpathy 思想的应用域具体化，**SkillForge 已有零件，缺组装方式**

## 阅读顺序

1. 当前 `index.md`（本文）— 摘要 + 5 决策 + V1 sprint 划分 + 完整 5 阶段路线图
2. [`mrd.md`](mrd.md) — 用户 8 轮对话原话 + 业内对照（11 系统 + 1 TMLR 综述）+ Karpathy autoresearch 7 抽象 5 哲学拆解 + 4 个痛点
3. [`prd.md`](prd.md) — V1 目标 / 非目标 / 工作流 / FR / AC / 5 D 决策 + 5 Q 澄清
4. [`tech-design.md`](tech-design.md) — Schema（t_research_finding 15 列）+ Quartz job + LlmExtractor 2-stage + Dashboard tab + Migration 顺序

## 业内对照（wiki 已沉淀，2026-05-28）

- [research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md) — 思想层：**7 抽象**（immutable harness / single mutable artifact / meta-mutable program.md / fixed budget / single metric / append-only log / status enum + git）+ **5 哲学**（人编程 meta-prompt 不写代码 / simplicity criterion / 不混改产物 / fixed budget 相对可比 / LOOP FOREVER）+ 跨域同形结构 + 6 反模式 + SkillForge 4 张力点（K-1~K-4）
- [research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md) — 应用层：11 系统全景 + 4 段收敛架构（信号 → 分类 → 存储 → 迭代）+ SkillForge 零件清单 + V1 K-1~K-4 完整设计 + **3 信号源融合**（production + autoResearch + rejected buffer）+ 5 阶段落地 + 6 反模式

## 完整 5 阶段路线图（V1 = Phase 1 only）

| 阶段 | 工作 | 估时 | V1 范围? |
|---|---|---|---|
| **Phase 0** | wiki + 需求包对齐（本文档）| 半天 | ✅ 已完成（本次提交）|
| **Phase 1** | **autoResearch arm MVP**：每周 scheduled + WebSearch + 2 阶段 LLM 提取 + `t_research_finding` 表 + dashboard 卡片（纯输出建议）| **2 周** | ⭐ **V1 完整范围** |
| **Phase 2** | K-1 拆 `optimizer_program.md` + K-3 加 `v_experiment_ledger` view + K-2 `complexity_delta` 列 | 2-3 周 | ❌ V2 |
| **Phase 3** | K-4 outer epoch loop + 3 早停 + **信号源融合**（autoResearch finding → candidate）+ predicted_impact + falsification stage | 2 周 | ❌ V3 |
| **Phase 4** | 跑 SkillsBench 公开 benchmark（86 tasks × 11 domains）+ 对外对比报告 | 1 周 | ❌ V4 |
| **Total** | **~8-10 周** | | |

> **V1 单独可上线 + 独立 ROI**：autoResearch arm 不依赖 outer loop / K-1~K-4，**单独 ship 即可让团队每周获得业内 best practice 自动汇总 + 人审建议** + 为 V3 信号源融合积累质量样本

## 5 个 ratify 决策（开 Plan 时再次确认 + 细化）

| # | 决策 | 选 | 备注 |
|---|---|---|---|
| **D1** | V1 范围 | **仅 Phase 1 autoResearch arm**（不含 K-1~K-4 outer loop） | 跟现有 14-stage 兼容，零侵入 |
| **D2** | 信号源 V1 范围 | **arxiv + GitHub trending**（blog 留 V2）| arxiv API + gh search trending repos，覆盖 80% 信号；blog 噪声高 |
| **D3** | LLM 提取 pipeline 阶段 | **2 阶段**：① 核心思想抽取（title/abstract → `idea_summary` / `novelty_json`）② 对照 SkillForge gap（→ `borrowable_pattern` / `gap_in_skillforge` / `recommended_action` / `importance_score`）| 跟现有 `MEMORY-LLM-SYNTHESIS 3 phase` 同构但简化 1 阶段，职责清晰 |
| **D4** | 触发频率 | **Weekly scheduled**（周一 03:00 UTC+8 cron）| 类比 `autoDream` 3-gate 思路简化为 weekly；V2 可改 event-driven（关键 repo release watch）|
| **D5** | 落地形态 | **`t_research_finding` 表 + dashboard 卡片**（V1 不直接接 candidate pipeline）| Iron Law 人审 gate；V2 通过的 finding 才进 candidate；**V1 不自动改任何 skill** |

> **D1 关键**：V1 严格控制范围 = 只加 1 张新表 + 1 个 scheduled job + 1 个 dashboard tab，**零侵入现有 14-stage**。这是 Phase 1 独立可上的根本前提。
>
> **D5 关键**：V1 纯输出建议，**不自动改任何 skill / prompt / 代码**。Iron Law 双层 gate（finding 审 + V2 candidate 审）后才进 candidate pipeline。理由：业内论文（[SkillsBench](https://www.skillsbench.ai) 实测）self-generated skill +0pp benefit，必须保留人审。

## 5 个 Q 澄清（待用户回答）

| # | 澄清 | 默认提案 | 影响 |
|---|---|---|---|
| **Q1** | arxiv 关键词清单具体哪些？是否做 YAML 可配置 | `skill evolution / agent harness / self-evolving agent / skill optimization / iterative agent improvement` 5 关键词起步；YAML 可配置 | Sprint 1 配置文件 |
| **Q2** | GitHub trending 阈值 | `stars >= 50 + last_commit < 14 days` + 关键词 filter | Sprint 1 search 参数 |
| **Q3** | 人审 UI 长什么样 | dashboard 新加 "Research Findings" tab + 卡片列表（标题 / 摘要 / 重要性 bar / accept/reject 按钮 / 转 backlog 按钮）| Sprint 2 React 设计 |
| **Q4** | `t_research_finding` → backlog ticket 自动化路径 | V1 半自动：人审 accept → 自动建 backlog item（`docs/todo.md` 加一行 + dashboard `status='backlogged'`）；V2 可全自动转 candidate | Sprint 2 后端集成 |
| **Q5** | WebSearch API 用现有还是新加 | **新加**：SkillForge 当前无 WebSearch 集成（需 verify），V1 用 [SerperAPI](https://serper.dev/)（AHE 同源选择）或 [Tavily](https://tavily.com/) | Sprint 1 集成 + .env 加 `SERPER_API_KEY` |

## 核心交付（V1，待开工时细化）

参见 [`prd.md`](prd.md) FR + AC + [`tech-design.md`](tech-design.md) 实现拆分。简略：

- **Sprint 1 — Schema + scheduled job 底层**（M / 跨 server + observability）
  - F1 Flyway migration：`t_research_finding` 15 列 + `(source_url, week)` 唯一索引（idempotency）
  - F2 Quartz `AutoResearchJob`：weekly cron（`0 0 3 ? * MON`），调 SerperAPI 5 keywords × 2 sources（arxiv + github），并行 fanout
  - F3 `LlmExtractor.java` 2-stage：Stage 1 抽 idea + novelty（Haiku-tier）/ Stage 2 对照 SkillForge gap（Sonnet-tier），复用 `LlmCallContext` 跨线程传递
  - F4 `consolidationLock` 模式（复用 autoDream）防并发跑
  - F5 每 finding 落 `t_llm_trace + t_llm_span` 关联（复用 observability）

- **Sprint 2 — Dashboard "Research Findings" tab**（M / dashboard React + REST）
  - O1 REST endpoints `/api/research-findings`（list / get / approve / reject）
  - O2 React tab + 卡片 UI：title / source icon / importance bar / idea_summary / borrowable_pattern / gap_in_skillforge / accept/reject/interested 按钮
  - O3 filter：status / importance threshold / source_type / date_range
  - O4 状态机：`pending → approved → backlogged` / `pending → rejected`（独立 mini-state-machine，**不接 14-stage**）
  - O5 accept → 自动建 backlog item（写 `docs/todo.md` 加行 + finding.status='backlogged'）

- **Sprint 3 — Iron Law + observability**（S / 复用现有 hook 框架）
  - I1 `LifecycleHookDispatcher` PreInsertHook 检查 finding 内容（防 prompt injection 注入 / 长度上限 / forbidden 关键词列表）
  - I2 `tengu_research_finding_*` events（discovered / extracted / approved / rejected / backlogged），跟现有 analytics pipeline 一致
  - I3 README 加 `t_research_finding` 表说明 + dashboard 截图 + 文档化关键词清单维护流程

- **Sprint 4 — 测试 + dogfood + metrics**（M / 跨 sprint 1-3）
  - T1 单测覆盖：`AutoResearchJob` LLM extraction quality（用 [SkillOpt arxiv 2605.23904](https://arxiv.org/abs/2605.23904) 当 golden expected output）+ idempotency
  - T2 dogfood 4 周：跑 4 轮 weekly，验证 finding 质量 / 噪声率（reject rate）/ 人审 UI workflow
  - T3 metrics 报告：每轮 finding 数 / accept rate / 跟 backlog 转化率 / LLM cost 实际值

## 跟相邻需求关系

- **跟 [OPT-LOOP-FRAMEWORK V1](../2026-05-27-OPT-LOOP-FRAMEWORK/)**：**互补不冲突**
  - OPT-LOOP-FRAMEWORK 抽 orchestrator framework + Run 实体泛化（**内部架构**）
  - AUTORESEARCH-OPTIMIZATION V1 加外部信号源（**信号层扩展**）
  - V3 (Phase 3) 时融合：autoResearch finding 进 `candidate_generating`（落入 OPT-LOOP-FRAMEWORK 的 Run 体系，`loop_kind='auto_research'`）

- **跟 [OPT-REPORT-V1](../OPT-REPORT-V1/)**：可选复用 `OptReportEntity` 模式
  - V1 不强复用（autoResearch 是独立 mini-state-machine，不入 14-stage）
  - V3 时 autoResearch run 可作为 `FlywheelRunEntity.loop_kind='auto_research'` 的实例

- **不冲突**：当前 backlog 无重叠

---

## 🔭 想再深一层？

- **看 V1 工程实施** → [tech-design.md](tech-design.md)（15 列 schema + Java 类树 + REST API + 9 复用模块清单 + Migration 13 步）
- **看痛点 + 用户 brainstorm 全过程** → [mrd.md](mrd.md)（8 轮对话原话 + 11 系统对照表 + Karpathy 7 抽象 5 哲学拆解 + 4 痛点）
- **看 V1 完整 FR/AC/D/Q** → [prd.md](prd.md)（6 FR + 8 AC + 5 D 理由展开含反例 + 7 风险 + 9 out-of-scope 清单）
- **看思想根基（wiki concept）** → [karpathy-autoresearch-thinking](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)（7 抽象 + 5 哲学 + 6 反模式 + Bilevel meta-iter）
- **看 V2-V4 K-1~K-4 完整设计（wiki concept）** → [iterative-skill-optimization](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md)（11 系统全景 + 4 段收敛架构 + 3 信号源融合）
- **看具体 paper 深度笔记**：
  - [skillopt](../../../../../research-docs/research/agent-harness-wiki/papers/skillopt.md) —— Microsoft 文本 SGD 完整范式
  - [agentic-harness-engineering](../../../../../research-docs/research/agent-harness-wiki/papers/agentic-harness-engineering.md) —— AHE 错峰世代 + falsification
  - [self-evolving-agents-survey](../../../../../research-docs/research/agent-harness-wiki/papers/self-evolving-agents-survey.md) —— TMLR 2026 27 作者综述
- **看相邻需求互补** → [OPT-LOOP-FRAMEWORK V1](../2026-05-27-OPT-LOOP-FRAMEWORK/index.md)
