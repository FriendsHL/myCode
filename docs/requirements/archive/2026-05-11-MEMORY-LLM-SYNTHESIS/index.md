# MEMORY-LLM-SYNTHESIS LLM 驱动梦境系统

---
id: MEMORY-LLM-SYNTHESIS
mode: full
status: mrd
priority: P1
risk: Full
created: 2026-05-11
updated: 2026-05-11
---

## 摘要

给 SkillForge 当前梦境系统（MEMORY-DREAM-CONSOLIDATION，2026-05-08 交付）补 **integration 能力** —— 用 LLM 做 dedup / synthesis（生成高阶 reflection）/ optimize（重写优化），覆盖 rule-based + embedding cosine 两条路径做不到的事。

## 阅读顺序

1. [MRD](mrd.md) — 用户原始诉求、业界调研、为什么现在做。
2. [PRD](prd.md) — 澄清后的产品需求、scope 切割、验收标准。
3. [技术方案](tech-design.md) — 5 phase 架构、12 决策点、schema migration、风险缓解。

## 当前状态

**状态**：r1 NEEDS_FIX_R2 → r2 PASS_WITH_NITS → **17 决策 ratify 完成**（D1 改 daily cron / D2-D17 走推荐）。**Ready for Phase 2 Dev**：1 BE Dev + 1 FE Dev 并行写 ~1800 LOC + V67 migration + ~30 测试 case + 顺手修 4 nit（F-N1~F-N4）。完整 review 记录：r1 `/tmp/review-tech-design-r1.md` / r2 `/tmp/review-tech-design-r2.md`。

**触发条件（2026-05-11 用户决策）**：

1. embedding 路径短期不通 —— Phase 0 cosine dedup `findEmbeddingsForActiveByUser` 因 `embedding.enabled=false` + pgvector 未装 + 无 API key，**当前 inactive**
2. SkillForge memory 池在 Phase 1/2 (TTL + capacity) 下持续累积，但**没有 integration 能力** —— 语义相近的 memory 占 ACTIVE 槽位，无人合并
3. LLM provider (`bailian` 默认 DeepSeek/Qwen) 已配，token 成本可控（单 user 一晚整理 < $0.01 估算）
4. 业界标准做法（Stanford Generative Agents reflection / MemGPT page-in/out / Mem0 graph）证明 LLM-driven memory consolidation 是可工程化的成熟模式

**Scope 决策（用户 2026-05-11 拍板：Full scope 立刻提队列）**：

| 动作 | 做？ | 边界 |
|---|---|---|
| dedup（合并同义）| ✅ | 替代当前不可用的 embedding cosine |
| synthesis（合成高阶 reflection）| ✅ | 新建 `type='reflection'` memory，**不删原始** |
| optimize（重写优化）| ⚠️ 保守 | 保留 `originalContent` + diff，dashboard 可 revert |
| delete（直接删除）| ❌ | 永远不让 LLM 删事实，降级走 rule-based STALE/ARCHIVED |

**下一步**：写 prd.md + tech-design.md → 用户 ratify 12+ 决策点 → Full pipeline 进 Phase 1 dev。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 交付 | — |

## 关联

- 前置：[MEMORY-DREAM-CONSOLIDATION](../../archive/2026-05-08-MEMORY-DREAM-CONSOLIDATION/index.md)（rule-based + embedding cosine 框架）
- 替代：原 V2 推迟项「LLM-driven weekly synthesis review」直接转入本期
- 关联：`MEMORY-DEDUP-COSINE-ACTIVATION`（embedding 路径 backlog，本期落地后**仍保留**作长期 fallback）
