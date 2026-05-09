# P9-5 Post-Compact 恢复

---
id: P9-5
mode: full
status: prd-draft
priority: P2
risk: Full
created: 2026-04-28
updated: 2026-05-09
---

## 摘要

Full compaction 完成后，往新 context 头部注入有预算上限的 recovery payload，让 agent 知道"最近碰过哪些文件 / 当前可用工具 / 注册的 skill / pending plan"，避免 compact 后 LLM 失忆。

## 范围调整记录

- 2026-04-28：P9-4（partial_head/partial_tail compact）和 P9-5（post-compact recovery）合并为同一需求包，P9-4 是 P9-5 的硬前置。
- 2026-05-09：调研 claude-code v2.1.88 后发现 P9-4 的痛点（局部清理但不要整段 LLM summary）已被 microcompact 模式更精确覆盖，且 microcompact **不调 LLM、零 token 成本**。P9-4 暂缓，移至 [deferred/P9-4-partial-compact](../../deferred/P9-4-partial-compact/index.md)，等 microcompact 落地后再评估是否仍需要按位置切的 partial compact。
- P9-5 解除"P9-4 前置"约束（FileStateCache 路线不依赖 partial compact），独立推进。

## 阅读顺序

1. [MRD](mrd.md)
2. [PRD](prd.md)
3. [技术方案](tech-design.md)

## 当前状态

- 数据来源候选已加第三方案 **C：内存 FileStateCache**（来自 claude-code post-compact 路线），prd.md / tech-design.md 标注待用户 ratify。
- 未决问题：recovery payload 范围（仅文件 vs 文件 + 工具 schema delta + skill listing）、注入格式（plain user message vs `<system-reminder>` 包装）。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
| 暂缓的 P9-4 | [deferred/P9-4-partial-compact](../../deferred/P9-4-partial-compact/index.md) |
| 相关历史方案 | [P9 归档方案](../../archive/2026-04-22-P9-tool-result-compaction/tech-design.md) |
| 相关历史方案 | [P9-2 归档方案](../../archive/2026-04-30-P9-2-tool-result-archive/tech-design.md) |
