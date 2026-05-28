# MRD — DREAMING-MEMORY-EXTENSION

> 创建：2026-05-26
> 状态：mrd

## 用户原话（2026-05-26）

跨多个 turn 整理：

> "Managed Agents：Anthropic 托管的 Agent 服务
> Dreaming（研究预览）：Agent 空闲时自动回顾 100 条历史对话，提取行为模式自我改进
> ……
> 这些相关能力 你能再深入的查一查吗？"

> "或者你去 旁边 research-docs 里面找下 今天也有相关的梳理。然后 我们列下 后续的一些迭代重点吧。"

> "你能给我一个 dreaming 的方案吗？只有 dreaming 的。尽量把 Dreaming 和 Outcomes 这个需求分开"

## 背景

### 触发事件

2026-05-06 Anthropic Code with Claude 大会发布 **Claude Managed Agents** SaaS，其中 **Dreaming**（Research Preview，beta header `dreaming-2026-04-21`）是 wiki ingest 后识别出对 SkillForge **能力扩展**最有借鉴价值的一条。

### 用户的研究历程

5-26 当天用户在 SkillForge 自家 dashboard 跑过 4 轮研究（session `6e2d3554`），其中：
- **Q3** (trace `e2912e02`) — Managed Agents / Dreaming / Outcomes / Multiagent 深入查
- **Q4** (trace `73763760`) — "对 SkillForge 有什么帮助" → mimo-v2.5-pro 输出 6 条初版建议

同日 `research-docs/research/agent-harness-wiki/` B 模式 source-grounded ingest 700 行 [`harness/anthropic-managed-agents.md`](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md)，**关 Dreaming 的 5 条 R-AMA-MEM-* 建议**：

| 编号 | 优先级 | 内容 |
|---|---|---|
| R-AMA-MEM-1 | P1 | `LlmMemorySynthesizer` 加 `synthesisInstructions: String` (≤4096 char) per-run 意图字段 |
| R-AMA-MEM-2 | P2 | 加 `t_memory_store_snapshot` entity 支持整 store 一键 rollback |
| R-AMA-MEM-3 | P2 | `LlmMemorySynthesizer` 暴露成 first-class trace span（扩 `span_kind` 4-enum + 每 cluster 一 span） |
| R-AMA-MEM-4 | P2 | verify SkillForge synthesis 是否跟 production loop 抢资源，若是抽 `MemorySynthesisExecutor` 独立 thread pool |
| R-AMA-MEM-5 | **P1** | `LlmMemorySynthesizer` 输入扩到 `sessions[]`（不只 reorganize 已有 memory）— **能力扩展，不只是优化** |

5-26 晚跑了 claude-code-guide Dreaming deep dive 补 `POST /v1/dreams` API 协议细节 + `dream.session_id` stream 业内独家 meta-observability + cost 估算 + 14 文件 hotspot。

### 痛点 / SkillForge 现状缺口

1. **Memory 路线只到 memory → memory，挖不出未观测 pattern**（R-AMA-MEM-5，**最大缺口**）
   - `MemoryClusterer` + `LlmMemorySynthesizer` 只能 reorganize 已沉淀 memory（业内称 reflection 路线）
   - 大量 pattern 在 session conversation 出现但**没沉淀成 observation 就消失了**（用户跟 agent 说话时透露的偏好、抱怨、隐含约束）
   - Anthropic Dreaming 的输入 `inputs[]` 是 `memory_store + sessions[]`，能从 session transcript 直接挖
   - 这是 wiki R-AMA-MEM-5 标记的"**能力扩展不是调味**"
2. **synthesis 跑批无 per-run 意图字段**（R-AMA-MEM-1）
   - 当前每次跑批走通用 prompt，用户没法说"本次重点 X、忽略 Y"
   - Anthropic Dreaming `instructions` ≤4096 char 提供该能力
3. **Memory store 级 rollback 缺失**（R-AMA-MEM-2）
   - 当前 `t_memory` in-place upsert + soft state machine（observation / reflection / optimized），rollback 要查 audit log 反推
   - Anthropic Dreaming 输入不可变 + 输出新 store 是更安全的 ACID 模式
4. **synthesis 跑批不是 first-class observability**（R-AMA-MEM-3）
   - 当前 synthesis 跑批不暴露 trace span；`LlmSpanEntity.span_kind` 3-enum（llm / tool / event）不含 memory_synthesis
   - dogfood 自家 observability 立刻有收益
5. **synthesis 是否跟 production loop 抢资源未 verify**（R-AMA-MEM-4）
   - 跑在主进程线程池，没显式隔离；wiki 标"先 verify，再决定"
   - 不能盲目隔离 — 万一现状 OK 就是浪费工作

## 限制

1. **架构边界**：SkillForge 是自托管 Spring Boot + 多 provider（7 `ProviderProtocolFamily` enum）路线；**不学** Anthropic 的 Hosted-only / Claude-only / ZDR 牺牲 这三条
2. **不引入新 Anthropic 依赖**：Managed Agents 是 Anthropic 闭源 SaaS，不能直接 call 它的 API；本需求包是**借鉴协议设计** + **在 SkillForge 自家代码里复刻**，不引入 `anthropic-managed-agents-client` 之类依赖
3. **触红灯文件清单**：`MemoryClusterer` 子系统在 [CLAUDE.md](../../../../CLAUDE.md) 核心文件清单 → 必走 Full pipeline + 对应 specialty reviewer（database-reviewer + java-reviewer）
4. **Outcomes 不在本包**：用户明确要求 "把 Dreaming 和 Outcomes 这个需求分开" — Outcomes 拆到 [`backlog/OUTCOMES-RUBRIC-FOUNDATION/`](../../backlog/OUTCOMES-RUBRIC-FOUNDATION/)，等本包 V1 ship 后单独拍是否升 active
5. **Multiagent 不在本包**：wiki 已结论 SkillForge `MAX_DEPTH=3` 路线比 Anthropic 单层 delegation 更深，不要倒退；R-AMA-MA-1/2 都不在 V1/V2

## 未澄清问题（开 Plan 时确认）

| # | 问题 | 候选答案 |
|---|---|---|
| Q1 | Phase 2 M1 prompt 三 slot 重写涉及 `LlmMemorySynthesizer` 当前 prompt template — 当前 prompt 是否有 dogfood "黄金样本" 防字节漂移？ | 候选：开 Plan 时先 grep `LlmMemorySynthesizer` 找现有 test fixtures，决定是否补 prompt-snapshot 测试 |
| Q2 | `SessionTranscriptProvider` 从 `t_session_event` 拼接 — chunk size / overlap / role filter 这些参数是否走 `@ConfigurationProperties`？ | 候选：是 — 跟 `MemoryClusterer` 5 const 一样 ConfigurationProperties |
| Q3 | Dreaming "100 sessions" 实际 SkillForge 该用多少？token budget cap 多少？目前 `t_memory` 表对单用户 active session 量级是多少？ | 开 Plan 时查 prod DB 拿 distribution |
| Q4 | F4 Audit-Mem-1 verdict 如果是 ❌（synthesis 确实跟 production loop 抢资源），V2 是否升 P1？ | 候选：是 — 安全 / 性能 bug 升 V2 P1，反之 V2 不做（D6 决策已隐含） |
| Q5 | M1 `instructions` 参数命名：跟 Anthropic Dreaming 同名 `instructions`，还是更具体的 `synthesisInstructions`（wiki R-AMA-MEM-1 建议）？ | 候选：`instructions` 简洁，跟 API 命名一致；method context 已说明是 synthesis；不用前缀 |

## 关联

- wiki [`harness/anthropic-managed-agents.md`](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) — ground truth for "Anthropic Dreaming 怎么做的"
- wiki [`harness/skillforge.md`](../../../../../research-docs/research/agent-harness-wiki/harness/skillforge.md) — ground truth for "SkillForge 现状"（特别 `MemoryClusterer` 5 const + `t_memory` 5 enum + 3 memory_kind）
- session 6e2d3554 Q3+Q4（trace `e2912e02` + `73763760`）— 5-26 mimo 自检初版，已被 wiki ingest 超越
- claude-code-guide Dreaming deep dive（5-26 晚）— 见 [`tech-design.md`](tech-design.md) Hotspot 表来源
- 姊妹包 [`backlog/OUTCOMES-RUBRIC-FOUNDATION/`](../../backlog/OUTCOMES-RUBRIC-FOUNDATION/) — 同次研究产出的 Outcomes 部分，独立 ship
