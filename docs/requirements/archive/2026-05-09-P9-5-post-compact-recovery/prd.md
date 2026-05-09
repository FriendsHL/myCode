# P9-5 PRD

---
id: P9-5
status: prd-ratified
owner: youren
priority: P2
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-28
updated: 2026-05-09
ratified: 2026-05-09
---

> **Ratify 记录（2026-05-09）**：用户确认 D1-D5 全部按推荐执行（D1 内存 FileStateCache / D2 仅文件 / D3 plain user message / D4 全局 @Component / D5 5 文件 × 5K token）。进 Full Pipeline 实施。

## 摘要

Full compaction 完成后，往新 context 头部注入有预算上限的 recovery payload。

## 目标

- Full compact 后自动注入最近 N 个文件的摘要。
- （待 ratify）注入工具 schema delta、skill listing、active plan 等其他 recovery 元素。
- 注入预算可配置（默认 5 文件 / 50K token）。

## 非目标

- **不**做 partial_head / partial_tail compact（迁移到 [deferred/P9-4](../../deferred/P9-4-partial-compact/index.md)）。
- **不**重复 P9-5-lite（pending FileWrite/FileEdit input 保留，P9-2 已交付）。
- **不**重新设计整个 system-reminder 框架（独立需求包）。
- **不**改 compact 触发 / 压缩算法本身（B1 / B2 / SessionMemory 三档保持不变）。

## 功能需求

- 在 `CompactionService` Phase 3（持久化前）拼接 recovery payload 到 summary 后。
- 数据来源（**待 ratify**）：内存 FileStateCache（候选 C）。
- payload 总预算可由 `application.yml` 配置；超预算按 file LRU 截断。
- 不破坏 tool_use ↔ tool_result 配对不变量。

## 决策清单（待用户 ratify）

| # | 决策项 | 推荐 | 理由（基于 2026-05-09 接入点调研） |
| --- | --- | --- | --- |
| D1 | 最近文件数据来源 | **C: 内存 FileStateCache** | 0 schema / 0 migration / FileTool 在 `execute()` 末尾 hook（`SkillContext.getSessionId()` 已有，无需改 SkillContext） |
| D2 | Recovery payload 范围 | **仅文件** | 调研发现：tool schema 每次随 request 全发（`ClaudeProvider.buildRequestBody:508-525`，cache_control 标记 ephemeral 命中 prompt cache）；skill listing 通过 `SkillLoaderTool` description 动态注入（`AgentLoopEngine:1582-1656`），每次 LLM 请求都包含。**recovery 不需要重注 tool delta / skill listing** |
| D3 | 注入格式 | plain user message 前缀 | 不依赖 system-reminder 框架（独立需求包推进中），等 system-reminder 落地后再统一迁移 |
| D4 | FileStateCache 存活范围 | **全局 `@Component` + `ConcurrentMap<sessionId, ConcurrentMap<path, entry>>`，session 终态仅 evict 该 session 的 entries** | 跨 session 命中需要全局视图（同一用户在不同 session 读同一文件可命中）；不挂 LoopContext（per-run 生命周期太短） |
| D5 | 文件预算 | 5 文件 / 5K token per file（约 25K 总 budget） | 与 claude-code `POST_COMPACT_MAX_FILES_TO_RESTORE=5` / `MAX_TOKENS_PER_FILE=5K` 对齐 |

## 验收标准

- [ ] Full compact 完成后，新 messages 头部含 recovery payload。
- [ ] FileStateCache 在 FileRead/FileWrite/FileEdit 后正确更新。
- [ ] 超预算时按 LRU 截断，不破坏 tool_use ↔ tool_result 配对。
- [ ] B2 / Preemptive / Post-overflow / SessionMemory 四种 full compact 路径都能正确触发 recovery 注入。
- [ ] payload 总 token 预算可由 `application.yml` 配置，缺省 50K。

## 验证预期

- 后端 unit + integration tests（CompactionService / FileStateCache）。
- 真实长 session sanity：手动触发 `/compact full`，观察 compact 后下一轮 LLM 仍知道刚才文件。
