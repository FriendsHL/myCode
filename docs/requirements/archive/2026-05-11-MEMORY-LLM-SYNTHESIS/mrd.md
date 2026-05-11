# MEMORY-LLM-SYNTHESIS MRD

---
id: MEMORY-LLM-SYNTHESIS
status: mrd
owner: youren
priority: P1
risk: Full
created: 2026-05-11
updated: 2026-05-11
---

## 用户原始诉求（2026-05-11）

> "LLM-driven synthesis 这个为什么推迟？"
>
> "我建议可以加上。因为现在还没有好的 embedding 接口，然后 我们要通过 llm 把这些 memory 进行一些 整合、删除、优化、等相关梳理。对 当前应该是有一些帮助的。"
>
> "你也可以联网搜索下。记忆梦境系统如何设计。"

## 当前痛点

### 1. 梦境系统当前只做了"睡眠"的一半

按生物学系统巩固理论（systems consolidation），睡眠时大脑做三件事：

- **selective forgetting**（synapse pruning） ✅ SkillForge Phase 1 TTL 已对应
- **integration**（片段拼合成 schema） ❌ **SkillForge 缺失**
- **replay**（重新激活强化） ✅ 通过 `recallCount` 间接对应

SkillForge 当前梦境系统（MEMORY-DREAM-CONSOLIDATION）只覆盖 forgetting + replay，**integration 完全空白**。

### 2. Embedding 路径短期阻塞

| 项目 | 状态 |
|---|---|
| `embedding.enabled` | `false`（yaml 默认） |
| pgvector extension | 未装（V7 silent skip） |
| EMBEDDING_API_KEY | 未配置 |
| 结果 | Phase 0 cosine dedup 调 `findEmbeddingsForActiveByUser` 抛异常 → log.warn 跳过 |

`MEMORY-DEDUP-COSINE-ACTIVATION` 已在 backlog 待激活，但拿到 embedding API 时间表不确定。**memory 池每天还在累积，等不起**。

### 3. Rule-based dedup 永远做不到 integration

cosine 0.85 只能做"几乎完全同义"的去重。下面三种情况它都搞不定：

- "用户喜欢用 PostgreSQL" + "用户提到 PG 是默认数据库" + "用户问 PG vacuum 怎么调" → 应合成 reflection "用户深度使用 PostgreSQL，关心运维"
- "周一开发了 X" + "周二开发了 Y" + "周三开发了 Z" → 应合成 "用户本周在做 X/Y/Z 主题的开发"
- "用户改主意了不用 PG 改用 MySQL"（新事实）+ 旧 "用户喜欢 PG" → 应触发 contradiction detection，保留新事实

## 业界调研（2026-05-11 联网检索整合）

### 1. Stanford Generative Agents (Park et al. 2023, "Smallville") — LLM-driven 路径标杆

三层 memory：

- **observation**（原始事实）
- **reflection**（LLM 派生的高阶 insight）
- **plan**（行动）

**Reflection 机制（论文细节）**：

- 累计 importance 达阈值（论文 150）触发，**每天平均 2-3 次**
- 取最近 100 条 observation 喂 LLM：「derive 3 条 high-level question」
- 对每条 question retrieval，再 LLM「基于 evidence 写 5 条 insight 陈述，标 source memory id」
- Reflection **不替换** observation，是派生层
- Reflections 形成树状结构：leaf = base observation / non-leaf = 越往上越抽象的 thought / **reflection 可基于其他 reflection**

**关键消融结果**：移除 reflection 组件 → agent 行为 **48 小时内退化成"重复、无上下文"**。证明 integration 能力对 long-running agent 是必需而非锦上添花。

**对 SkillForge 启示**：reflection-on-reflection 树状结构是 long-term 价值放大器，但本期不必一步到位（V2 加）。

### 2. MemGPT → Letta (2024 年 9 月 MemGPT 并入 Letta)

类比 OS 虚拟内存：

- **Core memory**：在 context window 内（RAM，小且快）
- **Recall memory**：历史对话可检索（disk cache，外部存储）
- **Archival memory**：长期存储，agent 通过 tool 调用查询（cold storage）

LLM **本身**作为 memory manager，调用 `core_memory_append` / `archival_memory_insert` / `archival_memory_search`。**没有定时梦境，是实时 self-editing**。

**对 SkillForge 启示**：Letta 路径"adaptive"但 token 贵（每次 LLM call 都做 memory 决策）。SkillForge 走 cron 路径更合适本期，但可以借鉴 Letta 的 tool 接口，给 agent 显式 `archive_memory(id, reason)` tool 作为补充入口（V2）。

### 3. Mem0 — Passive extraction 路径

Mem0 vs Letta 的核心差异：

- **Mem0**：vector storage + optional graph memory，三级 hierarchy（user / session / agent），"three lines of code 集成"，passive extraction 一致且 token-efficient，**但不能做 nuanced judgments**
- **Letta**：self-editing，adaptive but expensive

**对 SkillForge 启示**：Mem0 路径太被动（不能 push back 错误事实），SkillForge 已经走在 Letta 风格的"agent 主动 + cron 兜底"路上。不切换路线。

### 4. OpenClaw Dreaming (2026 开源系统) — 反 LLM-driven 的成熟样板

⚠️ **重要 push-back 案例**：业界一个**故意选择 rule-based** 的成熟系统。

**三阶段架构（凌晨 3 点 cron）**：

| 阶段 | 角色 | 是否用 LLM？ |
|---|---|---|
| **Light Sleep**（ingestion） | 读 daily memory file，Jaccard ≥0.9 去重，stage 到 short-term recall，记 "light signal" 强化分 | ❌ 纯 rule-based |
| **REM Sleep**（pattern extraction） | 7 天 lookback，concept-tag 频率统计找 candidate truths，记 REM signal | ❌ 主要 rule-based；只用 LLM 写"Dream Diary 美学叙述"（不影响 promotion） |
| **Deep Sleep**（promotion decision） | 6 信号加权打分 + 3 道阈值 gate 决定是否 promote 到 `MEMORY.md` | ❌ **纯 rule-based** |

**6 信号加权公式**：

| 信号 | 权重 | 含义 |
|---|---|---|
| Relevance | 0.30 | 跨 recall 的平均 retrieval 质量 |
| Frequency | 0.24 | 累积 short-term signal 数 |
| Query Diversity | 0.15 | 不同 query 上下文数 |
| Recency | 0.15 | 14 天 half-life 时间衰减 |
| Consolidation | 0.10 | 多天复发强度 |
| Conceptual Richness | 0.06 | concept-tag 密度 |

**3 道 gate（必须同时过）**：

- `minScore ≥ 0.8`
- `minRecallCount ≥ 3`
- `minUniqueQueries ≥ 3`

**OpenClaw 的设计哲学**：

> "LLM generation is confined to human-facing narratives. The Dream Diary is 'human-only' aesthetic output; it does not influence promotion logic."
>
> "These gates prevent one-off mentions from being promoted. A memory must demonstrate sustained, diverse relevance."
>
> Architecture prevents both **bloat**（too-aggressive promotion）and **loss**（under-promotion of valuable patterns）.

**对 SkillForge 的有力反 push**：是不是真的需要 LLM？

我对这个 push-back 的回应：

1. OpenClaw 假设 **Jaccard 0.9 文本相似度可用** —— 对英文短句可以，对中文 / 跨表达 / 同义换词的事实极弱（"用户喜欢 PG" vs "用户偏好 PostgreSQL" Jaccard 远低于 0.9）
2. OpenClaw 假设 **memory 有 query context 记录**（`minUniqueQueries ≥ 3` 这个 gate） —— SkillForge `t_memory` 当前**不记录"它被哪些 query 召回过"**，3 个 gate 里 2 个直接没数据
3. OpenClaw 走的是"高门槛 promotion，宁缺毋滥" —— 适合 markdown-as-memory 这种小池子；SkillForge 走 SQL + capacity 1500 双轨，已经接受了"快速吸收"的设计前提

**但 OpenClaw 的核心原则要尊重**：**LLM 输出绝不直接成为 memory，必须经 rule-based gate + 人工 review**。这变成本期方案的硬约束。

### 5. ICLR 2026 MemAgents Workshop / 综述论文

最新综述识别 LLM agent memory 的**五个 mechanism family**：

1. Context-resident compression（窗口内压缩）
2. Retrieval-augmented stores（向量库 / 图）
3. **Reflective self-improvement**（本期对应）
4. Hierarchical virtual context（MemGPT 路线）
5. Policy-learned management（RL learned）

进化阶段框架：**Storage → Reflection → Experience**（轨迹保存 → 反思精炼 → 经验抽象）。SkillForge 当前在 Storage 阶段，本期推进到 Reflection。

**2026 业界共识**：memory 已是 first-class architectural component，有独立 benchmark + 文献 + 工具生态。SkillForge 跟上这个方向是合理的。

### 6. Hallucination & Contradiction 风险（同期检索）

LLM-driven 路径的真实风险（多篇 2025-2026 survey 共同指出）：

- **Factual contradiction**：LLM 合成时遇到冲突事实可能生成"调和性"伪命题（"用户对 X 又喜欢又不喜欢"）
- **Knowledge integration pitfalls**：synthesis 输入是矛盾源时输出本身可能成为新 hallucination
- **Memorization hallucinations** 比 perception hallucinations 检测难

**缓解技术（业界已用）**：

- Semantic entropy 检测（Nature 2024）：测量 generation 的"含义不确定性"而非文本不确定性
- Source attribution + human review gate（Generative Agents 走的也是这条）
- 不让 LLM 删事实（hard rule，本期采用）

## 用户期望

按用户原话拆解："**整合、删除、优化、等相关梳理**"：

| 用户动词 | 对应能力 | 本期做？ |
|---|---|---|
| 整合 | dedup + synthesis | ✅ 做 |
| 删除 | LLM 主动判断该删 | ❌ **不做**（保护 fail-safe） |
| 优化 | 内容重写 | ⚠️ 保守做（保留原文 + revert） |
| 梳理 | 跨条 reflection | ✅ 做 |

**为什么"删除"不做** —— LLM 误判删事实不可逆，rule-based STALE/ARCHIVED 已覆盖"基于年龄/容量的安全淘汰"，再开 LLM 直接 delete 风险远大于收益。Hallucination survey 同样支持这一保守选择。

## 关键架构决策（受 OpenClaw + Generative Agents 影响）

最终选择 **Hybrid 路径**：

1. **LLM 当裁判**（不当 storage 写入者）—— 输出 "建议合并对 / 建议合成 reflection"，进 proposal 表
2. **Rule-based gate + 人工 review** —— 借鉴 OpenClaw "LLM 不参与 promotion logic" 原则
3. **保留 source attribution** —— 借鉴 Generative Agents `derivedFromMemoryIds[]`
4. **Reflection 不替换 observation** —— 借鉴 Generative Agents 派生层设计
5. **Contradiction detection 优先于 merge** —— 检测到"事实变化"时新事实赢，旧事实归档而非合并

## 不在本期范围内

- 真正的 entity/relation knowledge graph（Mem0 路线，工程量级别不同）
- Agent 主动触发 memory 整理（MemGPT/Letta 路线，需要 tool wrap，V2 加）
- Reflection-on-reflection 树状结构（Generative Agents 高级模式，V2 加）
- Multi-modal memory（image / audio embedding 进 memory）
- 跨 user memory 联邦（隐私模型 + 权限模型都没准备好）
- LLM 主动 delete（永不做，hard rule）
- 真实运行时检测 semantic entropy（hallucination 缓解高级模式，V2 看痛点）
