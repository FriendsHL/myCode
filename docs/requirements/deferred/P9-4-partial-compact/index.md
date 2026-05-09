# P9-4 Partial Compact（按位置切的部分压缩）

---
id: P9-4
mode: full
status: deferred
priority: P3
risk: Full
created: 2026-04-28
updated: 2026-05-09
---

## 摘要

P9-4 原计划在 `FullCompactStrategy` 增加 `compactUpTo`（压头保尾）和 `compactFrom`（压尾保头），并在 `ContextCompactTool` 暴露 `level=partial_head/partial_tail`。当前**暂缓**。

## 暂缓决策

- **日期**：2026-05-09
- **原因**：调研 claude-code v2.1.88 后发现 P9-4 partial_head 想解决的痛点（"局部清理 + 不要整段 LLM summary"）已被 **time-based microcompact + cached microcompact** 模式更精确覆盖：
  - microcompact 按内容粒度（老 tool_result）精切，P9-4 partial_head 是按位置粒度（前 N 条）粗切
  - microcompact **不调 LLM**、零 token 成本；P9-4 partial 仍要 LLM 做 mini summary
  - microcompact 触发器自然（"老 tool_result 多了"），P9-4 阈值"前 X 条压一下"不好定
  - microcompact 天然按 tool_use ↔ tool_result 配对清理，P9-4 partial 要小心边界切割
- partial_tail（压尾保头）几乎没人用——尾部就是当前活跃区域，无人会压最近的。

## 重评触发

如果以下任一条件出现，重新打开 P9-4：

- microcompact（time-based + 本地 replace 版）落地后用户报告"还是想要按位置切的 mini summary"
- 出现 microcompact 不能解决的真实场景（如：前段是 setup 阶段 + 大量 assistant text + 不含 tool_result，microcompact 摘不到）
- 用户/agent 主动需要 "我自己挑一段压" 的工具控制力

## 当前替代路径

1. **超载严重**：触发现有 B2 Full（整段 LLM summary）
2. **轻度冗余**：触发现有 B1 Light（按规则去重 + 截大 tool_result + 折叠重试）
3. **未来工作（P9-5 之后）**：time-based microcompact —— 见 [P9-5 归档包内变更记录](../../archive/2026-05-09-P9-5-post-compact-recovery/index.md)

## 历史来源

- 原合并需求包（active/P9-4-P9-5-compaction-recovery）已重组为 P9-5 独立包并于 2026-05-09 交付归档至 [archive/2026-05-09-P9-5-post-compact-recovery](../../archive/2026-05-09-P9-5-post-compact-recovery/index.md)
- claude-code 调研记录：见 P9-5 PRD / tech-design 的范围调整章节
