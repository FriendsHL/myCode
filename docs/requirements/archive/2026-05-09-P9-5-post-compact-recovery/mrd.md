# P9-5 MRD

---
id: P9-5
status: mrd
source: user
created: 2026-04-28
updated: 2026-05-09
---

## 用户诉求

长 session 触发 full compact 后，agent 经常"失忆"——不知道刚才在改哪个文件、可用工具列表是什么、有什么 pending plan。要么瞎摸要么重新 Read 一遍最近文件，浪费 token 和 turn。

## 背景

- P9-5-lite 已修复 pending FileWrite/FileEdit input 保留（P9-2 一并交付）。
- 完整 P9-5 仍需要：定义"最近操作文件"等 recovery 元素的来源，以及如何拼接到 compact 后的 context。
- 2026-05-09 调研 claude-code v2.1.88 发现 post-compact 注入 5 个最近文件 + tool schema delta + agent/skill listing，文件来源是**内存 FileStateCache**（非 trace 反查也非新表）。

## 期望结果

- Full compact 完成后，新 context 头部自动包含一段 recovery payload。
- payload 至少含最近 N 个文件摘要；可能还含工具 schema delta、skill listing、active plan。
- 总预算不超过给定 token 上限，超出按重要性截断。

## 约束

- 不重复 P9-5-lite（pending tool input 保留）已做的事。
- 实现前必须先决定 recent-file 数据来源。
- 不能破坏 tool_use ↔ tool_result 配对不变量。

## 未决问题

- [ ] **核心阻塞**：最近文件数据来源选 A（trace spans 反查）/ B（新表 t_session_file_activity）/ **C（内存 FileStateCache，claude-code 路线）** 哪个？
- [ ] Recovery payload 范围：仅文件 vs 文件 + 工具 schema delta + skill listing + active plan？
- [ ] 注入格式：plain user message 前缀 vs `<system-reminder>` 包装（与 system-reminder 框架统筹）？
