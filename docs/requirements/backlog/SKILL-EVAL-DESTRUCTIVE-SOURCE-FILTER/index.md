---
id: SKILL-EVAL-DESTRUCTIVE-SOURCE-FILTER
mode: mid
status: backlog
priority: P1
risk: Low
created: 2026-05-19
follows: SKILL-CREATOR-WITH-EVAL, SKILL-CREATOR-PHASE-1.6
---

# SKILL-EVAL-DESTRUCTIVE-SOURCE-FILTER — 排除"修改代码 / 文件"类 source session 当 eval scenario

## 痛点 (跟 SKILL-EVAL-CHILD-SANDBOX 同源)

Phase 1.6 dispatchEvaluation 拿 source session 真 messages 当 scenario task 喂 child SubAgent. **如果 source session 是 "改 X 代码" 类 destructive task**, child agent 跑这个 scenario 时真活 modified production state. 即使 Layer 1 sandbox (disable Edit/Write) 起作用, child agent 仍可能借 Bash (e.g. `python -c 'open(...).write(...)'`) 真活 modify file.

更彻底 fix: **从源头排掉 destructive source session**, 让 eval 只跑 read-only-ish 任务 (review / 分析 / 生成报告 / SQL 查 / API 调).

## 范围 (~0.5d)

### Heuristic Phase (~0.3d, 80% 准确度)

- `SkillCreatorService.dispatchEvaluation` 加 `isDestructiveSession(sessionId)` check
- `Heuristic`:
  - Session messages 含 `tool_use` 真 `name in ('Edit', 'Write')` → destructive
  - Session messages text 含 keyword 'rm / sed -i / tee / redirect / 修改 / 编辑 / 删除' → destructive
  - System agent 跑 session (cron 真不当 eval source) → 排
- 如 destructive → `dispatchEvaluation` 返 400 `DESTRUCTIVE_SOURCE`, dashboard FE Modal 显 "This source session contains modification tasks, not suitable for eval. Pick another source."

### LLM Judge Phase (~0.2d 升级, 95% 准确度)

- Heuristic 边界 case 真活 fallback 调 LLM:
  - Prompt: "Read this session transcript, answer: is this a 'modify code/file' task that would alter production state if re-run? (yes/no + 1 sentence reason)"
- LLM 出 yes → destructive
- LLM 出 no → safe

### FE 改动

- `TriggerEvaluationModal` 加 destructive warning (BE 真活返 400 时 show)
- `SkillDraftDetailDrawer` Source tab 加 destructive badge (如 BE flag 已 known destructive)

## 关联

- 真因: 跟 [SKILL-EVAL-CHILD-SANDBOX](../SKILL-EVAL-CHILD-SANDBOX/index.md) 同源 (Phase 1.6 dogfood bug)
- 借鉴: cc agentskills.io 真 evaluation_guide 说 "eval scenarios should be hermetic (no external state modification)"

## 触发条件

跟 SKILL-EVAL-CHILD-SANDBOX 一起 (Layer 1 + Layer 2). Phase 1.7 候选.
