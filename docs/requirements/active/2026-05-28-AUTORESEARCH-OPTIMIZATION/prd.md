# PRD — AUTORESEARCH-OPTIMIZATION V1（autoResearch arm 独立）

> 💡 **30 秒摘要**：V1 = autoResearch arm MVP（2 周）。**每周** arxiv + GitHub trending 扫 → **LLM 2-stage** 抽取（Stage 1 Haiku-tier 抽 idea + novelty / Stage 2 Sonnet-tier 对照 SkillForge gap）→ **Iron Law `PreInsertHook`** 拦 prompt injection → **`t_research_finding` 表** 入库 → **dashboard 卡片** 人审 → accept 自动建 backlog item / reject 进 buffer 防 14 天重复抓。**AC-2 关键 KPI**：4 周 dogfood 后 **accept rate > 60%**。**Cost**：$0.6/week SerperAPI + ~$2.2/week LLM = **月成本 ~$10**。

> 🧭 **角色导航**：
> - 👤 **你要 ratify** → §1 目标 + §2 非目标 + §6 D 决策展开理由 + §7 Q 澄清
> - 🎓 **你想理解 why** → [mrd.md](mrd.md) + 本文 §6 D 决策展开（每条含 Pro/Con/反例）+ §8 风险缓解
> - 🛠 **你要开工** → [tech-design.md](tech-design.md) + 本文 §4 6 FR + §5 8 AC（含 SkillOpt arxiv 2605.23904 当 golden 单测）
> - ⏱ **你只有 1 分钟** → 上面 "💡 30 秒摘要" + §1 目标 + §6 D 决策清单

## 1. 目标

让 SkillForge 团队**每周自动获得业内 skill 进化 / agent harness 进化 / self-evolving 方向 best practice 汇总**，通过 Iron Law 人审 gate 后进 backlog；为 V3 飞轮 `candidate_generating` 阶段提供**外部信号源**基础设施（V3 时跟 production 飞轮 + rejected buffer 形成 3 信号源融合）。

## 2. 非目标

- ❌ V1 **不**自动改任何 skill / prompt / 代码框架（不接 14-stage）
- ❌ V1 **不**做 outer epoch loop（K-4，Phase 3）
- ❌ V1 **不**做 falsification 检查（K-4，Phase 3）
- ❌ V1 **不**拆 `optimizer_program.md`（K-1，Phase 2）
- ❌ V1 **不**改 14-stage state machine（Phase 2-3 才碰）
- ❌ V1 **不**做公开 benchmark（SkillsBench，Phase 4）
- ❌ V1 **不**做 blog 抓取（V2 扩展）
- ❌ V1 **不**做 event-driven 触发（V2，本期 weekly schedule 即可）

## 3. 用户工作流

```
[周一 03:00 UTC+8] AutoResearchJob 自动触发（Quartz cron）
  ↓
acquire consolidationLock（复用 autoDream 模式，防并发）
  ↓
for keyword in [skill evolution, agent harness, ...]:
   SerperAPI.searchArxiv(keyword, last_7d)
   SerperAPI.searchGithub(keyword, stars>=50, last_commit<14d)
  ↓
并行 fanout LLM extraction：
   Stage 1 (Haiku-tier)：title + abstract → idea_summary + novelty_json + primary_domain
   Stage 2 (Sonnet-tier)：Stage1 输出 + SkillForge gap_summary doc → borrowable_pattern + gap_in_skillforge + recommended_action + importance_score (0-10)
  ↓
PreInsertHook 检查（Iron Law，长度 / forbidden keywords / injection 防御）
  ↓
写入 t_research_finding（status='pending'）
  ↓
[周一 10:00] dashboard "Research Findings" tab 推送 + 飞书通知
  ↓
[团队 review] accept / reject / mark "interested"
  ↓
accept → 自动建 backlog item (docs/todo.md 加行) + finding.status='backlogged'
reject → reason 写入 + finding.status='rejected' + 进 rejected_research_buffer 防 14 天内重复抓
mark interested → status='approved' 但不建 backlog（暂存）
```

## 4. 功能需求（FR）

### FR-1 Scheduled Job
- **FR-1.1**：Quartz `AutoResearchJob` 每周一 03:00 UTC+8 自动触发（cron `0 0 3 ? * MON`）
- **FR-1.2**：admin only 手动触发 endpoint `/api/admin/research/trigger`，跟 scheduled 走同一逻辑
- **FR-1.3**：`ConsolidationLock` 模式（复用 [autoDream `consolidationLock.ts`] 等价 Java 实现）防并发 —— 同时只允许 1 个 job 跑
- **FR-1.4**：单次 job 总 timeout 30 分钟（防 LLM 调用爆炸）

### FR-2 External Search
- **FR-2.1**：调 SerperAPI（Q5 拍板后定，[serper.dev](https://serper.dev/)），并行 fanout 5 关键词 × 2 数据源 = 10 search calls
- **FR-2.2**：关键词清单走 `application.yml` 可配置，默认：
  - `skill evolution`
  - `agent harness`
  - `self-evolving agent`
  - `skill optimization`
  - `iterative agent improvement`
- **FR-2.3**：arxiv 取 `last_7_days` filter；GitHub trending 取 `stars >= 50 + last_commit < 14d + has README`
- **FR-2.4**：单次 search 最多返 10 条 / keyword / source，单次 job 上限 100 candidate finding（防爆）

### FR-3 LLM 2-Stage Extraction
- **FR-3.1**：Stage 1 用 SkillForge 现有 `LlmCallContext` 调 LLM 抽取
  - Input：`title + abstract + first 2K char of body`
  - Output JSON：`{idea_summary: string, novelty_json: {core_novelty, differentiators[]}, primary_domain: string}`
  - Model：默认 Haiku-tier（`LLM_MODEL_FAST` env var），约 $0.003/call
- **FR-3.2**：Stage 2 对照 SkillForge 现状
  - Input：`Stage 1 输出 + skillforge_gap_summary.md`（SkillForge 当前 4 surface / 14-stage / Iron Law / 已有零件清单 doc，**人工维护，每月 review**）
  - Output JSON：`{borrowable_pattern: string, gap_in_skillforge: string, recommended_action: string, importance_score: int 0-10}`
  - Model：默认 Sonnet-tier（`LLM_MODEL_SMART` env var），约 $0.015/call
- **FR-3.3**：每个 finding 落 `t_llm_trace + t_llm_span`（root trace_id 串联 1 个 job 的所有 finding；每个 finding 是子 span）
- **FR-3.4**：LLM 调用失败重试 1 次；2 次都失败 → finding 不写入 + tengu_research_extraction_failed event

### FR-4 t_research_finding 表 + 状态机
- **FR-4.1**：表 schema 15 列（详见 `tech-design.md` §1）
- **FR-4.2**：状态机：
  ```
  pending → approved → backlogged
     ↓         ↓
  rejected ← rejected
  ```
- **FR-4.3**：Idempotency：`(source_url, date_trunc('week', discovered_at))` 唯一索引，**7 天内同 URL 不重复抓**
- **FR-4.4**：`rejected_research_buffer`（实质是同表 `WHERE status='rejected' AND reviewed_at > now() - 14 days`）—— 下次 LLM 调用前查此 buffer，相同 idea_summary 命中则跳过

### FR-5 Dashboard "Research Findings" tab
- **FR-5.1**：React tab 在 dashboard 顶级导航（位置：跟现有 `Reports` / `Flywheel` tab 同级）
- **FR-5.2**：卡片列表 UI，每张卡片显示：
  - title + source_type icon（arxiv / github）+ arxiv_id 或 GitHub stars 数字
  - importance_score bar（0-10，颜色编码）
  - idea_summary（默认折叠 2 行，点击展开）
  - borrowable_pattern + gap_in_skillforge + recommended_action
  - 按钮：`Accept → Backlog` / `Reject` / `Interested (later)`
  - 链接：原 URL 一键跳转 + trace_id 进 observability 页
- **FR-5.3**：filter：status（pending / approved / rejected / backlogged，多选）/ importance_score min slider / source_type / date_range
- **FR-5.4**：accept → 后端 POST `/api/research-findings/{id}/approve` → 自动建 backlog item（写 `docs/todo.md` 加行 + finding.status='backlogged'）
- **FR-5.5**：reject → 必填 reason 输入框
- **FR-5.6**：list 排序：默认 `importance_score DESC, discovered_at DESC`

### FR-6 Iron Law + Observability
- **FR-6.1**：`LifecycleHookDispatcher` PreInsertHook 检查（复用现有 hook 框架）
  - 长度上限：`idea_summary` ≤ 2000 chars，`abstract_text` ≤ 8000 chars
  - Forbidden keywords：`ignore previous instructions / disregard all / system override / [PROMPT_INJECTION]` 等明确 injection 字符串列表
  - 失败 → 不写入 + log warn + `tengu_research_finding_blocked_by_hook` event
- **FR-6.2**：`tengu_research_*` events：
  - `tengu_research_job_started / completed / failed`
  - `tengu_research_finding_discovered`（每条 finding）
  - `tengu_research_finding_extracted`（Stage 2 完成）
  - `tengu_research_finding_approved / rejected / backlogged`（人审动作）
- **FR-6.3**：每次 job 写 1 个 `t_llm_trace` 根节点（`root_trace_id`），所有 finding 的 LLM 调用作为子 span 串联

## 5. 验收标准（AC）

- **AC-1**：连续 4 周自动跑 + 无 crash，每周至少产 5-15 条 finding（少于 5 条 → 关键词过窄 alert）
- **AC-2**：finding 质量评估：**accept + interested rate > 60%**（reject rate < 40%）—— 4 周 dogfood 后评估
- **AC-3**：accept → backlog item 自动建（从 dashboard 点 accept 到 `docs/todo.md` 加行 < 5 秒）
- **AC-4**：Iron Law 工作：PreInsertHook 拦截 100% 长度超 / forbidden 关键词输入（单测覆盖）
- **AC-5**：Idempotency：同 URL 同 week 跑 2 次 finding 数无变化（数据库唯一索引保证）
- **AC-6**：observability：`tengu_research_*` events 完整 + `t_llm_trace` 可追溯（dashboard observability 页能看到每个 finding 的 LLM 调用 cost）
- **AC-7**：cost 上限：单次 job LLM cost < $5，单月总 cost < $30（4 weeks × ~30 finding/week × Stage1 $0.003 + Stage2 $0.015 ≈ $2.2/job × 4 = $9）
- **AC-8**：[SkillOpt arxiv 2605.23904](https://arxiv.org/abs/2605.23904) 当 golden case，单测 verify Stage 1 extract 包含 "text SGD" + "epoch/batch/lr/momentum" + "52/52 cells SOTA"，Stage 2 verify `borrowable_pattern` 包含 `momentum / rejected buffer` 关键词

## 6. 5 D 决策（同 index.md，此处展开理由）

详见 [index.md](index.md) D1-D5。理由展开：

### D1（V1 范围）：仅 Phase 1 autoResearch arm
**Pro**：
- autoResearch arm 单独 ROI 高（每周自动业内汇总 + 半天工程 → 节省 80% 调研时间）
- 不依赖 K-1~K-4 outer loop（可独立 ship）
- 工程量可控（2 周 + 4 周 dogfood）
- **为 V3 信号源融合积累质量样本**（4 周 dogfood 数据让 V3 判断 finding → candidate 转化率）

**Con**：
- 不直接驱动 skill 优化（要等 V3）
- 价值用户感知较弱（不是用户面 feature）

**反例**：如果 V1 直接做 K-1~K-4 outer loop → 工程量 8-10 周，**且 finding 质量未知**情况下做 outer loop = 浪费

### D2（信号源）：arxiv + GitHub trending
**Pro**：
- 覆盖 80% 关键信号
- arxiv API 标准化、易抓
- GitHub trending 自动反映社区关注度

**Con**：
- blog 噪声高、有付费墙
- 视频（YouTube / 会议录像）不抓
- 中文社区（知乎 / 飞书 / 微信公众号）不抓

**对照 AHE 选择**：AHE `evolve_agent` 也用 SerperAPI 做 web search，证明这条路可行

### D3（LLM 阶段）：2 阶段
**Pro**：
- 跟 `MEMORY-LLM-SYNTHESIS 3 phase` 同构（dedup / reflection / optimize），但简化为 2 phase
- 职责清晰：Stage 1 抽思想（domain expert 视角），Stage 2 对照 gap（SkillForge 视角）
- 双模型分层（Haiku Stage 1 + Sonnet Stage 2）成本可控

**Con**：
- 3 阶段（如再加 importance ranking）更细但 cost ×1.5

**反例**：1 阶段（Stage 1 + Stage 2 合并）→ prompt 过长 + LLM 注意力分散 + 输出 JSON schema 多 → 出错率高

### D4（频率）：Weekly
**Pro**：
- arxiv 每天新论文 100+ → daily 噪声太高
- weekly 是经验上的最优粒度（[Karpathy autoresearch] 跑 100 exp/night = daily 内部，但**外部世界更新粒度天然 ≥ weekly**）
- 跟周一团队 standup 节奏一致

**Con**：
- 重要 paper 可能延迟 ≤ 6 天发现
- 关键 GitHub release event 不能即时响应

**V2 升级方向**：event-driven（关键 repo release webhook）+ 保留 weekly 兜底

### D5（落地）：t_research_finding + dashboard
**Pro**：
- Iron Law 必须保留（[SkillsBench arxiv 2602.12670](https://arxiv.org/abs/2602.12670) 实测 self-generated skill +0pp benefit，证明无人审会噪音淹没）
- V1 不接 candidate pipeline → 不破现有 14-stage
- 表 + dashboard 是最低工程量 + 最高可观察性

**Con**：
- V1 不能自动改 skill（要等 V3）
- 团队需要每周 review（虽然有 dashboard，但仍是人力）

**反例**：V1 直接接 candidate pipeline → 4 周 dogfood 期内噪声 finding 污染 14-stage → 难回滚

## 7. 5 Q 澄清（待用户回答）

| # | 澄清 | 默认提案 | 如果选其他选项 |
|---|---|---|---|
| **Q1** | arxiv 关键词清单具体哪些？是否做 YAML 可配置 | 5 关键词起步 + YAML 可配置（详见 FR-2.2）| 如要加更多关键词 → 注意 cost 线性增长 |
| **Q2** | GitHub trending 阈值 | `stars >= 50 + last_commit < 14d` + 关键词 filter | 阈值放宽 → 噪声指数增长 |
| **Q3** | 人审 UI 长什么样 | dashboard 新 "Research Findings" tab + 卡片列表（详见 FR-5）| 如做 inline review（finding 直接 in-line dashboard 首页） → 工程量翻倍 |
| **Q4** | `t_research_finding` → backlog 自动化 | V1 半自动（accept → 写 `docs/todo.md`） | V1 全自动（accept → 直接建 requirement 目录）→ 风险高，V3 再做 |
| **Q5** | WebSearch API 用哪个 | **新加 SerperAPI**（AHE 同源选择，[serper.dev](https://serper.dev/)）| Tavily（[tavily.com](https://tavily.com/)）替代 → 成本 / 召回率对比待 spike |

## 8. 风险 + 缓解

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| LLM Stage 2 对照 SkillForge gap 质量低 → finding 不可操作 | 高 | 中 | `gap_summary` doc 人工维护 + 每月 review；V1 dogfood 4 周校准 + 单测用 SkillOpt 当 golden |
| SerperAPI cost 过高 | 中 | 低 | weekly job + 5 keywords × 2 sources = ~30 API calls/week × $5/1000 calls = **~$0.6/week** |
| arxiv 搜索关键词覆盖不全 → 漏关键论文 | 中 | 中 | YAML 关键词可配置 + 人工每月加新词 + 定期跟用户回顾 |
| backlog 被大量 finding 灌爆 | 中 | 中 | dashboard accept rate < 40% 触发 alert + 人工 prune；importance < 5 自动 archive |
| Prompt injection 通过 abstract 注入 | 高 | 低 | PreInsertHook + LLM 调用前 sanitize（移除 `<system>` tag / 长重复模式 / 异常字符）|
| 人审跟不上（每周积 100+ finding 看不完） | 中 | 中 | importance_score DESC 排序 + dashboard 高亮 top 10 + 飞书每周推 top 5 |
| `gap_summary` doc 不及时更新 → Stage 2 输出过时 | 中 | 高 | doc 每月强制 review + Stage 2 prompt 引用 wiki [`concept/iterative-skill-optimization.md`](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md) §4 SkillForge 零件清单作为底稿 |

## 9. Out-of-scope 备注（V2+ 才做）

- **K-1 拆 `optimizer_program.md`** → Phase 2
- **K-2 加 `complexity_delta`** → Phase 2
- **K-3 ledger view** → Phase 2
- **K-4 outer epoch loop** → Phase 3
- **predicted_impact + falsification** → Phase 3
- **3 信号源融合**（finding → candidate）→ Phase 3
- **SkillsBench 公开打榜** → Phase 4
- **Bilevel meta-iter**（optimizer_program.md 自我演化）→ V99
- **blog 抓取** → V2
- **Event-driven trigger**（关键 repo release watch）→ V2
- **Pareto frontier top-K**（Sentient EvoSkill 借）→ V2

---

## 10. 🔭 想再深一层？

- **看完整 D/Q + 5 阶段路线图** → [index.md](index.md)
- **看 tech 实现细节**（15 列 schema + Java 类树 + REST API + 9 复用模块） → [tech-design.md](tech-design.md)
- **看痛点 + 用户 brainstorm 全过程** → [mrd.md](mrd.md)（8 轮对话原话 + 11 系统对照表 + 4 痛点）
- **看思想根基（wiki concept）** → [karpathy-autoresearch-thinking](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md) + [iterative-skill-optimization](../../../../../research-docs/research/agent-harness-wiki/concept/iterative-skill-optimization.md)
- **看具体 paper 深度笔记** → [SkillOpt](../../../../../research-docs/research/agent-harness-wiki/papers/skillopt.md) / [AHE](../../../../../research-docs/research/agent-harness-wiki/papers/agentic-harness-engineering.md) / [TMLR 综述](../../../../../research-docs/research/agent-harness-wiki/papers/self-evolving-agents-survey.md)
- **看相邻需求** → [OPT-LOOP-FRAMEWORK V1](../2026-05-27-OPT-LOOP-FRAMEWORK/index.md)
