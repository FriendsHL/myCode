# MRD — AUTORESEARCH-OPTIMIZATION 市场需求文档

## 1. 用户原话（2026-05-28 brainstorm 历史，8 轮对话）

### 第 1 轮：触发
> "后续我想把 autoResearch 的能力 放到我们平台上。可以轮训优化 skill、prompt、甚至是我们内部的代码框架。"

理解偏 #1：误以为是 backlog `AUTORESEARCH-OPTIMIZATION` 旧项（外部抓论文 → 输出建议）+ 现有飞轮的 outer loop 升级。

### 第 2 轮：用户引论文
> "微软的 SkillOpt（2026.05）是该方向最强力的系统性工作 ... CoEvoSkills / AutoSkill / MemSkill ... 现在的 某个agent的session + 批量标注 + 聚合内容 + 归因分析 + 人工确认优化方向 → A/B测试 进行指标评分 判断是否采纳。一次完整的 分析优化结束了，但是 其实可以继续给归因分析 然后再优化 再A/B测试 再根据指标结果 判断是否采纳 不断的轮训直到 达到最大轮训次数。"

理解：用户想 wrap 14-stage 为 outer iter loop（对应 K-4 思想）。

### 第 3 轮：用户引 AHE
> "https://github.com/china-qijizhifeng/agentic-harness-engineering 这个是优化代码框架的。"

理解：4 surface（含 code framework）全要做。

### 第 4 轮：assistant 研究 SkillOpt + 整理 SkillOpt / AHE / SkillForge 三足鼎立对照

### 第 5 轮：用户列 6 篇论文 + 综述 + benchmark
fan-out 5 parallel agents 拉 **MemSkill / Self-Evolving Agents Survey TMLR 2026 / SkillsBench / Sentient EvoSkill** + cross-ref 6 篇已有 wiki notes（autoskill / embodiskill / evoskill-multi-agent / evoskills-coevolutionary / learning-without-losing-identity / self-evolving-embodied-ai）。

### 第 6 轮：用户重定义 autoResearch
> "我说的 autoResearch 是指 github 上卡帕西的一个项目。我理解 刚刚说的 这种 skill 进化 框架进化 都是采用的 autoResearch 的思想。"

⭐ **关键转折**：用户明确 **Karpathy `autoresearch`**（630 行 + 3 文件 + MIT，53.5k stars）是这套思想的最简形态，所有那些花哨论文 / 工程都是它的应用域具体化。

### 第 7 轮：用户拍板写 wiki
> "A+B吧" — 两篇 wiki 同时写

⭐ **本需求包基础**：基于已写入的两篇 wiki 起 PRD：
- `concept/karpathy-autoresearch-thinking.md`（思想层）
- `concept/iterative-skill-optimization.md`（应用层）

### 第 8 轮：用户拍板做 SkillForge PRD
> "1吧" — 起 SkillForge 需求包，Phase 1 autoResearch arm 单独独立

⭐ **V1 范围**：Phase 1 autoResearch arm only（不含 K-1~K-4 outer loop）

---

## 2. 业内对照（详见 wiki 两篇）

### 11 个系统 + 1 个综述

| 系统 | 类型 | 核心 | 跟 SkillForge 关系 |
|---|---|---|---|
| **Karpathy autoresearch** | GitHub 53.5k stars MIT | 630 行 + 3 文件，**思想最简形态** | ⭐ V1 哲学起点 |
| **microsoft/SkillOpt** | GitHub MIT + arxiv 2605.23904 | 文本 SGD，52/52 cells SOTA | V3 借 momentum + epoch |
| **AHE（Curry09）** | GitHub + arxiv 2604.25850 | Terminal-Bench 第 3，错峰世代 + falsification | V3 借 falsification |
| **EmbodiSkill** | paper（wiki 已有）| ALFWorld 93%，SkillDefect vs ExecutionLapse 4-class | V3 借失败二分类 |
| **AutoSkill** | paper（wiki 已有）| 从用户对话提 SKILL.md | 参考 confirmation |
| **MemSkill（清华）** | GitHub + arxiv 2602.02474 | memory ops as learnable skills | V2/V3 memory 层 |
| **Sentient EvoSkill** | GitHub ⭐600+ + arxiv 2603.02766 | Pareto frontier 多目标 | V2 Pareto |
| **EvoSkill multi-agent** | paper（wiki 已有）| 失败驱动 skill discovery | confirmation |
| **CoEvoSkills** | paper（wiki 已有）| Generator + Verifier 共演化 | V3+ 探索 |
| **Learning-Without-Losing-Identity** | paper（wiki 已有）| ECM + persistent identity | confirmation `EVOLUTION_FORK` |
| **Self-Evolving Embodied AI** | paper（wiki 已有）| 5-self paradigm 综述 | 总纲 framing |
| **SkillsBench** | benchmark + arxiv 2602.12670 | 86 tasks × 11 domains | ⭐ V4 公开打榜 |
| **Self-Evolving Agents Survey TMLR 2026** | survey 27 作者 arxiv 2507.21046 | 4-axis taxonomy（What/When/How/Where）| 综述层 framing |

### 收敛架构（11 系统都在做同一件事）

```
   ① 信号                ② 分类               ③ 存储               ④ 迭代
  ──────────         ─────────────         ───────────         ──────────
  failure trace      typed enum            versioned           outer loop
  prod session       failure category      skill module        accept gate
  user prefs         verifier score        identity preserve   rejected buffer
  rollouts                                                     meta-skill
```

### SkillForge 已有零件清单

| 4 段 | SkillForge 现状 | 状态 |
|---|---|---|
| **① 信号** | `t_llm_trace + t_llm_span` 3-kind + **production session** + `origin='production/eval'` 双轨 partial index | ⭐ **比所有 paper 都强** |
| **② 分类** | `MEMORY-v2 kind 3-enum` + `SkillSource 8-enum`（业内最多） | ✅ 已有 |
| **③ 存储** | `t_skill_proposal` + `EVOLUTION_FORK` + 4 surface 独立 | ✅ 已有 |
| **④ 迭代** | 14-stage state machine，但**只跑 1 轮** | ❌ **缺 outer loop** |
| **治理（Bonus）** | **Iron Law 人审 gate**（11 篇全场没有） | ⭐ **业内独家护城河** |

⭐ **本 PRD 解决"缺外部信号源"问题**：autoResearch arm。其他 K-1~K-4 进 V2-V4。

---

## 3. Karpathy autoresearch 7 抽象 5 哲学（V1 设计哲学根基）

详见 wiki [`concept/karpathy-autoresearch-thinking.md`](../../../../../research-docs/research/agent-harness-wiki/concept/karpathy-autoresearch-thinking.md)。摘要：

### 7 个核心抽象
1. **Immutable harness**（eval / data / 常量不可改）
2. **Single mutable artifact**（agent 只改 1 个文件）
3. **Meta-mutable instruction**（人编辑 `program.md`，不改代码）
4. **Fixed budget**（每实验固定 5 min wall-clock）
5. **Single metric**（`val_bpb` 越低越好）
6. **Append-only log**（`results.tsv` 5 列永不删）
7. **Status enum + git**（`keep / discard / crash` + 每实验 1 commit）

### 5 条设计哲学
1. **人不写代码，人编程 program.md**（人的杠杆点上移）
2. **Simplicity criterion**（复杂度本身是负价值，删代码且指标不退步 = win）
3. **Single mutable file**（多产物并改 = 归因灾难）
4. **Fixed budget, not fixed result**（实验本质是相对的）
5. **LOOP FOREVER, 人按停**（没有 max_iter，只有 max_compute_budget）

### V1 直接落地的部分

- 哲学 **③ Single mutable**：autoResearch finding 输出**只触发 1 个 surface 的 backlog item 建议**（不混改）
- 哲学 **⑤ LOOP FOREVER**：scheduled weekly，**不让用户每次点跑**
- 抽象 **6 Append-only log**：`t_research_finding` 是 append-only ledger，状态枚举 + reason 字段保留
- 抽象 **3 Meta-mutable**：V1 关键词清单走 YAML，不写死 Java（K-1 思想的 V1 微版本）
- 抽象 **1 Immutable**：人审 gate 流程不可被 agent 自动绕过（Iron Law 保护）

V2-V4 落 K-1~K-4 + Pareto + SkillsBench 公开打榜。

---

## 4. 痛点拆解

### 痛点 1：团队跟踪业内进展靠手动 + 分散
- arxiv 每天 100+ 篇 agent / skill / evolution 相关论文，**人工跟踪不可能**
- GitHub trending 散在多个 watcher tool（Slack / 飞书 / RSS）
- **机会成本**：错过 SkillOpt（52/52 SOTA）2 周才接触，错过 AHE（Terminal-Bench 第 3）等
- 本次需求自身就是**用户在 2026-05-28 偶然提及 SkillOpt 才触发 wiki ingest**，证明缺自动化

### 痛点 2：外部信号没接入飞轮
- 飞轮当前只有内部 production session 1 个信号源
- 外部世界的 best practice（**SkillOpt rejected buffer / AHE falsification / EmbodiSkill 4-class**）**应该是 candidate 生成的灵感来源**，但当前完全断链
- V3 信号源融合阶段必须先有外部信号 ledger，所以 V1 必须先建 autoResearch arm

### 痛点 3：研究 → 落地路径长
- 看完 paper → 写 wiki → 起 PRD → 落地 backlog → sprint，**至少 1-2 周**
- 半自动化（finding → 自动建 backlog item）可缩到 **1-2 天**
- 节省 80% 业内调研时间

### 痛点 4：研究信号难复现 / 难审计
- 当前"我读了什么 paper → 借了什么 idea"散在 wiki / log / 对话历史
- **缺统一 ledger** → SkillForge 改动归因到论文来源时找不到证据链
- V3 时 candidate 的 `research_credit` 字段需要指回 `t_research_finding.id`，所以 V1 必须先建表

---

## 5. 旧 backlog 项关系

- 当前 [docs/todo.md:21](../../../todo.md) 行 `AUTORESEARCH-OPTIMIZATION` 仍标 `backlog`，描述跟本 V1 一致：
  > "AutoResearch 自动调研外部（论文/blog/GitHub via WebSearch+WebFetch）→ 提取业界最佳实践 → 给出 SkillForge 自身的 skill description / SystemAgent prompt / 代码框架 优化建议。**跟飞轮 OPT-LOOP-FRAMEWORK 互补**：飞轮看自家生产 session 事实，autoResearch 看外部研究。"

- **本 PRD 承接该 backlog 项 + 收窄到 V1 = Phase 1 autoResearch arm only**。原项描述提及的"代码框架优化"留 V3 + Phase 2-3 完成。

- 待用户 ratify 本 PRD 后：
  - `docs/todo.md:21` 状态 `backlog` → `prd-draft`
  - `docs/README.md:34` 状态 `backlog` → `prd-draft`
  - 加 link 到本 requirement 目录
