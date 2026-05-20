# FLYWHEEL-PER-RUN —— 飞轮 per-run 跟踪视图

---
id: FLYWHEEL-PER-RUN
mode: mid
status: active
priority: P2
risk: Low
created: 2026-05-20
updated: 2026-05-20
---

## User Request

> "数据飞轮这个我感觉还是需要再迭代的。当前这个看起来纯粹是一整个流程。但是实际上我需要的是我跑一次飞轮之后这个任务运行到哪了，是否有问题。可以类似数据飞轮的可观测看板。"
>
> 后续 confirm: 视觉形态复用现有 DAG 即可，问题是当前 step 是 aggregate 概念而不是 per-run journey 阶段。

跟 FLYWHEEL-FLOWCHART (commits `65c8643` + `389845d`, 2026-05-20) 的 framing 漂移 —— 现有 panel 显示 pipeline 拓扑 + aggregate metric，operator 看到的是"整个 pipeline 当前 in-flight 多少 / lag 多少"，**但看不到"我刚 approve 的那个 OptEvent 现在跑到哪了 / 有没有出错"**。

本包在现有 panel 加 mode toggle，per-run mode 下显示具体某个 OptEvent 在 DAG 上的当前位置 + 错误。

## Acceptance

- `/insights` 第 5 tab `flywheel` 顶部加 `[Aggregate | Per-Run]` mode toggle，默认 Aggregate（保留现有行为）
- Per-Run mode 切换后：
  - 左侧出现 **"Recent Runs" sidebar**（list 最近 N=20 个 OptimizationEvent run）
  - 每行显示：agent name / pattern signature snippet / current stage chip / age (e.g. "2h ago") / status emoji（🔄 进行中 / ✅ 完成 / ⚠️ 卡住 stage>2h / ❌ 失败）
  - 点 sidebar 中的 run → DAG 高亮该 run 的当前 step（pulse 用绿环替代 + 节点边框加粗）
  - DAG 上 pre-OptEvent 节点（ENTRY + ① ② ③）灰化为 "context" 显示（表示这些是 run 创建之前的事，不属于 run journey）
  - 选中 run 的 surface 自动决定显示哪条 surface（user 不再 manually 选 surface tab，surface tab 在 per-run mode 下 disabled 或 hidden）
  - 点节点 → Drawer 显示该 run 在该 stage 的 start time（updatedAt 推断）+ 错误（如果 stage='*_failed' 或 child session error）
- Aggregate mode（默认）保留原有行为（双 tab + 全节点 metric 聚合）
- BE 加 1 个 endpoint `GET /api/flywheel/runs?agentType=&surface=&limit=` 返回最近 N OptimizationEvent + 关联 SkillDraft / abRun 数据
- 复用现有 `useFlywheelObservability` hook 数据（aggregate mode）+ 新 `useFlywheelRuns` hook（per-run mode）
- Iron Law: 核心 7+1 BE + 核心 3 FE git diff 0；不动 schema；BE 加 endpoint 只 read-only

## Implementation Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| Run 定义 | 一个 OptimizationEvent | 最自然的"飞轮过一遍"，覆盖 attribute → A/B → promote 全程 |
| 视图形式 | 同 panel mode toggle（不另起 page）| User 明确说"再迭代"，复用已 ship 的 flowchart |
| Sidebar 位置 | 左侧滑出（width 320px）+ collapse 按钮 | 不挡 DAG 视图，operator 可 expand/collapse |
| Run 数据粒度 | MVP only current state（不做 stage history 表）| 不需要新 schema，Mid 档可控；future iter 加 history table 走 Full |
| Pre-OptEvent 节点处理 | 灰化但保留显示 + 标"context"label | 比纯隐藏更易让 operator 理解"这部分 happens before"，可教学 |
| Per-run 错误聚合 | OptEvent.stage='*_failed' + child session.runtime_status='error' join 出来 | 现有数据，FE/BE 都能算 |
| BE endpoint | 新 `GET /api/flywheel/runs` aggregate | 不污染现有 `/api/attribution/events`；future per-run 业务可独立扩展 |
| Sidebar 默认显示哪些 run | 进 24h + 状态!=promoted/discarded | "还在跑"的 run 最有价值；终态 run 可通过 filter 看 |

## Implementation Notes

**Scope (intended file list, scope 外不动)**:

**BE (3 文件)**:
1. `skillforge-server/src/main/java/com/skillforge/server/controller/FlywheelController.java` (NEW) — `GET /api/flywheel/runs?agentType=&surface=&limit=&hideTerminal=true` 返回 `FlywheelRunDto[]`：含 optEventId / agentId / agentName / surface / patternSignature / currentStage / errorLabel / startedAt / lastUpdatedAt / candidateSkillId / abRunId
2. `skillforge-server/src/main/java/com/skillforge/server/service/FlywheelRunsService.java` (NEW) — aggregate logic：query `OptimizationEventRepository` 最近 N + join `t_pattern.signature` / `t_skill_draft` / `t_skill_ab_run` / child session status 拼 DTO
3. `skillforge-server/src/test/java/.../FlywheelRunsServiceTest.java` (NEW) — unit test + 1 roundtrip IT (Jackson 字段名 + ISO-8601)

**FE (5 文件)**:
4. `skillforge-dashboard/src/api/flywheel.ts` — 加 `listFlywheelRuns({agentType, surface, limit, hideTerminal})` wrapper
5. `skillforge-dashboard/src/hooks/useFlywheelRuns.ts` (NEW) — useQuery 拉 runs，refetchInterval 30s
6. `skillforge-dashboard/src/components/flywheel/FlywheelRunsSidebar.tsx` (NEW) — 左侧 sidebar 组件，列出 runs + collapse / sort / filter chip
7. `skillforge-dashboard/src/components/flywheel/FlywheelFlowchart.tsx` — 加 `[Aggregate | Per-Run]` toggle + per-run mode 切换逻辑 + 把 `activeRunId` state 传给 FlywheelNode（per-run 高亮）+ disable surface tab when per-run + selected
8. `skillforge-dashboard/src/components/flywheel/FlywheelNode.tsx` + `StepCard.tsx` — 接 `activeRunStage` / `activeRunIsCurrent` props 用 className 区分 "current step"（per-run 高亮）vs "context step"（灰化）
9. `skillforge-dashboard/src/components/flywheel/FlywheelStepDrawer.tsx` — per-run mode 下显示该 run 在该 stage 的 start time + error；aggregate mode 行为不变

**测试**：
10. `skillforge-dashboard/src/components/flywheel/__tests__/FlywheelRunsSidebar.test.tsx` (NEW) — 渲染 + click 触发 onSelect + filter chip 真起效 4-5 case
11. `skillforge-dashboard/src/components/flywheel/__tests__/FlywheelFlowchart.test.tsx` — 加 2 case：mode toggle 切换 + per-run mode 高亮 current step 正确

**CSS**：
12. `skillforge-dashboard/src/components/flywheel/flywheel.css` — sidebar 样式 + mode toggle 样式 + per-run "current" pulse + "context" 灰化样式

**Iron Law 自检**：
- ✅ 核心 7+1 BE 0 diff (FlywheelController + FlywheelRunsService 不在核心清单)
- ✅ 核心 3 FE 0 diff (Chat.tsx / ChatWindow.tsx / Layout.tsx)
- ✅ 无 schema migration
- ✅ 无 @Transactional 改动（read-only finder）
- ✅ Jackson contract 走 footgun #6 roundtrip test 覆盖

## Pipeline

Mid 档（跨栈 BE + FE / 新 endpoint / 不碰核心文件 / 无 schema / 无 protocol / brief <800 字 actual coded content）—— Mid 不循环对抗 1 轮：

- TeamCreate `flywheel-per-run`
- BE-Dev Opus + FE-Dev Opus 并行
- java-reviewer + ts-reviewer Sonnet 对抗 1 轮（不循环）
- Judge (主会话) → PASS 进 Phase Final / blocker → 升 Full 决策
- Phase Final mvn + tsc + vitest + 浏览器目检 (mode toggle 真切 / sidebar 列出 runs / 点 run DAG 高亮) + commit

## MVP 不做

- 历史回放 / stage transition 时间线（需要新 schema `t_optimization_event_history`，留 Full 档独立立项）
- run 之间的 diff 对比
- run-level 操作按钮（cancel / retry — observability ≠ ops console）
- 实时 WS push（manual refresh + 30s polling 兜底）
- per-run alerts / 通知

## 验证

- `mvn -pl skillforge-server -am test` BUILD SUCCESS + 新 BE 测试 PASS
- `cd skillforge-dashboard && npx tsc --noEmit` EXIT=0
- `npx vitest run src/components/flywheel/__tests__/` 全 PASS (含新加 case)
- 浏览器目检：
  - 默认 Aggregate mode 跟现有一致
  - 切到 Per-Run mode → 左侧 sidebar 出现 + 列 N 个 run
  - 点 sidebar 中的 run → DAG 高亮 current step + pre-OptEvent 节点灰化 + surface tab 被锁定（per-run 决定）
  - 点节点 → Drawer 显示该 run 在该 stage 的 info（start time / error）
  - 切回 Aggregate → 恢复原有行为，无 state leak
