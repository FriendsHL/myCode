# FLYWHEEL-FLOWCHART —— 飞轮工作流流程图视图

---
id: FLYWHEEL-FLOWCHART
mode: mid
status: active
priority: P2
risk: Low
created: 2026-05-20
updated: 2026-05-20
---

## User Request

> "我想要的是那种 类似工作流的流程图"
> 后续补充："如果是运行中的话 可以想想 卡片模块上有没有什么动态动画什么的 表示正在运行 一圈绿色缓慢闪烁表示 正在运行。"

跟 FLYWHEEL-VISUAL-STATUS (commits `092267f` + `8cecb17`, 2026-05-20) 的 framing 漂移 —— 该交付实际是 card list with metric 风格（Grafana-like），而用户要的是 **n8n / Dagster / Airflow DAG** 风格 boxes + 连线 + 数据流向。本包替换该 card-style panel。

## Acceptance

- `/insights` 第 5 tab `flywheel` 渲染为 **DAG flowchart**（不是 vertical card list）
- 节点之间用箭头表数据流（ENTRY → ① 标注 → ② 聚类 → ③ 归因 → G1 → ④ candidate → ⑤ A/B → G3 → 决策；G2 SkillDraft 接在 ④/⑤ 之间）
- AUTO + HYBRID 节点 in-flight > 0 时 **慢闪绿色 ring 1.5s 周期** 表运行中
- USER GATE 节点不闪（静态红 `[PEND N]` chip 表 attention，per 2026-05-20 user 推荐）
- ENTRY / DORMANT 节点不闪
- 相邻 in-flight 节点之间 edge 动画 dashed flow（表数据流动中）
- `prefers-reduced-motion` 用户：动画退化为静态 outline + 不闪
- 复用 `useFlywheelObservability` hook 数据（**BE 0 改动**）
- 替换 `FlywheelObservabilityPanel`（card-style）不做 view toggle

## Implementation Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| 图库 | **React Flow** (xyflow.com, MIT, ~50KB gzipped) + **dagre** auto-layout | 业界 React DAG 标准；auto-layout 避手算坐标；node/edge 完全自定义 |
| 替换 vs toggle | 替换 | toggle 增加复杂度且没人想真用两种视图；当前 card 视图被推翻 |
| 节点 body | 复用 StepCard 简化版（2-3 metric，不是 5）| flowchart 节点要紧凑，5 维太挤；保留 in-flight count + lag + drill-down chip |
| Running 动画 | CSS keyframe pulse box-shadow，AUTO+HYBRID + (in-flight>0 OR cron lastRunStatus='running') 时激活 | 用户 spec 绿色慢闪；CSS 比 JS 动画轻 |
| Edge 动画 | React Flow `animated: true` for in-flight 相邻节点 | 内置 dashed flow；默认静态实线节省视觉噪音 |
| USER GATE 动画 | 不闪（静态红 PEND chip）| 等待人工 ≠ 运行；闪了让 operator 误以为系统在动 |
| Activity feed | 保留（在 flowchart 下方）| 时间序列信息互补 DAG 拓扑信息 |
| Obsolete 删除 | `FlywheelObservabilityPanel.tsx` / `FlywheelTimeline.tsx` / `FlywheelObservabilityPanel.test.tsx` 删 | 替换不是"顺手清理"；新 FlywheelFlowchart 完整覆盖 |

## Implementation Notes

**Scope (intended file list, scope 外不动)**:

新文件:
- `skillforge-dashboard/src/components/flywheel/FlywheelFlowchart.tsx` — 主 panel，React Flow + dagre + node/edge 构造
- `skillforge-dashboard/src/components/flywheel/FlywheelNode.tsx` — custom node component (React Flow nodeType)，包 StepCard 紧凑版 + running 动画 class
- `skillforge-dashboard/src/components/flywheel/__tests__/FlywheelFlowchart.test.tsx` — 渲染 + running 动画 class + edge 动画 class + reduced-motion fallback 测试

修改:
- `skillforge-dashboard/src/pages/FlywheelObservability.tsx` — 替换 `<FlywheelObservabilityPanel>` 为 `<FlywheelFlowchart>`
- `skillforge-dashboard/src/components/flywheel/flywheel.css` — 加 `@keyframes flywheel-running-pulse` + `.fw-node--running` + `prefers-reduced-motion` fallback + React Flow node/edge overrides
- `skillforge-dashboard/src/components/flywheel/StepCard.tsx` — 加 `compact?: boolean` prop（compact mode 只渲 2-3 metric 给 FlywheelNode 用；默认 full 保留 backward compat — 但 backward 不需要因为旧 panel 删了，可以直接重构 StepCard 为 compact-only，简化更好）
- `skillforge-dashboard/src/hooks/useFlywheelObservability.ts` — 加 `isRunning: boolean` per step derived field（lastRunStatus='running' OR in-flight>0 for AUTO+HYBRID 节点）
- `skillforge-dashboard/package.json` + `package-lock.json` — 加 `reactflow` + `dagre` deps

删除:
- `skillforge-dashboard/src/components/flywheel/FlywheelObservabilityPanel.tsx`
- `skillforge-dashboard/src/components/flywheel/FlywheelTimeline.tsx`
- `skillforge-dashboard/src/components/flywheel/__tests__/FlywheelObservabilityPanel.test.tsx`

**不动**:
- `ActivityFeed.tsx` (保留作 flowchart 下方组件)
- `types.ts` (STEP_CATALOGUE / computeHealth 复用)
- `api/flywheel.ts` (3 endpoint wrapper 复用)
- 4 page URL routing 改动（Insights / SkillList / SessionList / SkillDrafts）— 已 ship，不动
- BE 任何文件（0 改动）
- 核心 7+1 BE + 核心 3 FE
- Layout / global CSS / 别 page

## Iron Law 自检

- ✅ 核心 7+1 BE 0 diff
- ✅ 核心 3 FE 0 diff（Chat.tsx / ChatWindow.tsx / Layout.tsx）
- ✅ 无 BE 改动 / 无 schema / 无 @Transactional
- ✅ 新增 dep 仅 `reactflow` + `dagre`（业界主流 MIT）

## Animation Spec

```css
@keyframes flywheel-running-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.6); }
  50%      { box-shadow: 0 0 0 6px rgba(34, 197, 94, 0); }
}
.fw-node--running { animation: flywheel-running-pulse 1.5s ease-in-out infinite; }
@media (prefers-reduced-motion: reduce) {
  .fw-node--running { animation: none; outline: 2px solid rgba(34, 197, 94, 0.8); }
}
```

Edge: React Flow `edge.animated = true` 当 source + target 都 in-flight。

## Pipeline

Mid 档（pure FE / 新 dep / 替换 shipped UI / 0 BE / 0 schema / 不碰核心文件）—— 走 Mid 不循环对抗 1 轮：

- TeamCreate `flywheel-flowchart`
- FE-Dev Opus 1 dev
- ts-reviewer + code-reviewer Sonnet 对抗 1 轮（不循环）
- Judge (主会话) → 0 blocker PASS 进 Phase Final / 1+ blocker 回主会话决策升 Full
- Phase Final tsc + vitest + npm build + 浏览器目检 hover ring 动画 + commit

## MVP 不做

- 历史回放 / time travel
- 横向 vs 纵向 layout toggle（用 dagre 默认）
- node drag-and-drop save 自定义 layout
- React Flow minimap 缩略图（DAG 不算特别大）
- Edge label（数据计数）
- Subscriber-based real-time update（保留 manual refresh + 30s tick from prev useFlywheelObservability）

## 验证

- `npx tsc --noEmit` EXIT=0
- `npx vitest run src/components/flywheel/__tests__/FlywheelFlowchart.test.tsx` PASS
- `npm run build` 0 net new error (pre-existing 2 baseline OK)
- 浏览器目检：
  - DAG 节点 + 箭头正确渲染（dagre auto-layout）
  - in-flight > 0 节点真闪绿色 ring
  - in-flight = 0 节点静态
  - USER GATE 红 chip 静态
  - reduced-motion 媒体 query 时不闪只 outline
  - 节点点击 drill-down 跳现有 page
