# FLYWHEEL-VISUAL-STATUS PRD

---
id: FLYWHEEL-VISUAL-STATUS
status: design-draft
owner: youren
priority: P2
risk: Low
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
updated: 2026-05-19
---

## 2026-05-20 Phase 1.0 取证后 ratify — 选定 1B + 2B 全功能版本

Phase 1.0 取证报告 (`/tmp/phase1.0-recce-flywheel-obs.md`) 发现 2 个大坑必须正面处理：

### 坑 1 — 6/11 drill-down URL 是 dead link

原 PRD 假设 drill-down 跳现有 page 即可。实际：`/insights/optimization-events` / `/agents/{id}/skill-evolution` / `/skill-ab-runs` / `/canary/rollouts` 这 4 个 route **根本没注册**（OptEvent / SkillEvolution / AB / Canary 都是嵌进 Insights / SkillList 的 tab/panel，不是独立路由）；`/insights/patterns?agentId=X&status=open` 路由存在但不读 useSearchParams（silent drop）。

### 坑 2 — 至少 3 个 BE endpoint gap

| Gap | 影响 |
|---|---|
| `GET /api/skills/abtest` 无全局列表 endpoint（只 per-skill） | ⑤ A/B step 无法跨 agent / 跨 skill 监控 |
| `GET /api/canary/rollouts` agentId 必填 | ⑦⑧⑨ canary 无全局视图（虽 dormant） |
| `t_skill_draft.source` 列存在但 endpoint 不 filter | ENTRY E2/E3 (upload vs extracted) 无法分类 |

### 用户决定（2026-05-20）：1B + 2B 全功能

**1B — URL-driven 路由 + tab/filter**:
1. **`Insights.tsx`**：加 `useSearchParams`，URL `?tab=patterns|optimization|behavior-rules|dynamic-sim` 控 tab；tab 内嵌套 filter 也支持 URL（如 `?tab=optimization&stage=proposal_pending&agentId=X`）。tab + filter 互通 (in-page 改也同步 URL)。
2. **`SkillList.tsx`**：加 `useSearchParams`，URL `?skillId=X&panel=evolution|abtest|canary` 控 auto-open SkillEvolutionPanel / SkillAbPanel / CanaryPanel。
3. **`SessionList.tsx`**：加 `annotated` URL param 真消费 → BE filter（如果 BE 支持，否则 client-side filter 后端拉所有 + FE 过滤）。
4. **`SkillDrafts.tsx`**：加 `useSearchParams` 读 `status` URL param（当前 statusFilter 是 useState）。

**2B — 补 3 个 BE endpoint**:
1. **`GET /api/skills/abtest?agentId=X&status=running|completed|failed&page=&size=`** —— 全局 A/B run listing，跨 skill aggregate。新 Repository query。
2. **`GET /api/canary/rollouts?agentId=&surfaceType=&stage=`** —— 改 agentId optional（当前 required）。改 `CanaryController` + Repository query。
3. **`GET /api/skill-drafts?source=upload|extracted|natural-language|marketplace`** —— 加 source filter。改 `SkillDraftController` + Repository。

**额外**：
- `GET /api/chat/sessions?createdAfter=&userId=` —— ENTRY chat 24h 计数 efficient path（FE 拉全量过滤可接受，但若 session 数 > 500 改 BE filter）—— **本期暂缓**，FE 全量过滤兜底。

### Iron Law 自检（更新后）

| 文件 | 影响 |
|---|---|
| 核心 7+1 BE | ❌ 0 触碰（SkillAbtestController / CanaryController / SkillDraftController 都不在核心清单） |
| 核心 3 FE | ❌ 0 触碰（Insights / SkillList / SessionList / SkillDrafts 都不在核心 3）|
| Schema migration | ❌ 0（全用现有列 + 新 query）|
| @Transactional / 协议 | ❌ 0（read-only endpoint + 既有 surface） |

### 工时估算

- 原 Mid 2-3d (pure FE) → **Mid+ ~5-7d**：FE ~3-4d（含 4 page URL routing + 主 panel + 4 类节点 + 4 维 metric + 健康颜色 + 测试），BE ~2-3d（3 endpoint + repo query + Spring + 单测 + roundtrip）。BE + FE parallel 实际 wall-clock ~4-5d。

### Pipeline 调整

- Phase 2: BE-Dev + FE-Dev 并行（Opus 双 Dev）
- Phase 3: java-reviewer + ts-reviewer + code-reviewer 三 reviewer 并行（Sonnet 各自 diff-in-prompt）+ Judge (主会话 Opus)
- Phase Final: tsc + npm build + mvn test 三件套 + e2e drill-down 真活 + commit

---

## 2026-05-19 启动 — 重新定位为「飞轮可观测面板」(observability)

用户 confirm 启动时改框架：从「status panel」升级为「flywheel observability」，定位等同于 Grafana panel for the flywheel。新增范围如下，原 9 step swim-lane 设计保留作为骨架。

### N1 四类节点（不只 AUTO，加 USER + HYBRID + ENTRY）

| 节点类型 | 例子 | 视觉 |
|---|---|---|
| 🤖 **AUTO** | ① 标注 / ② 聚类 / ③ 归因 / ④ candidate 生成 / ⑤ A/B（auto-trigger 路径） | 蓝色边框 |
| 👤 **USER** | G1 OptimizationEvent approve/reject / G2 SkillDraft review+trigger eval / G3 promote/discard 决定 | 橙色边框 + 红 chip `[PEND N]` |
| 🔀 **HYBRID** | ⑤ A/B（可 auto 或 manual trigger） | 紫色边框 |
| 🚪 **ENTRY** | E1 user chat session / E2 upload skill zip / E3 extract from session / E4 直接写 prompt | 绿色边框，画在最上 |

ENTRY 节点回答「今天进入飞轮的源信号有几条」，operator 看到飞轮入口流量。

### N2 每个节点 4 个 observability 维度（替代原"count + last activity"两维）

```
┌─ ③ 🤖 归因  attribution-curator hourly ─────────────┐
│ in-flight: 3     lag: last run 47m ago [✅ healthy] │
│ today: 12 dispatched, 8 → pending, 4 noise         │
│ recent error: (none) / 1 dispatch_initiated timeout │
│ → /insights/optimization-events?stage=…             │
└────────────────────────────────────────────────────┘
```

| 维度 | 含义 | 数据源 |
|---|---|---|
| **in-flight count** | 当前在该 step / 状态的对象数 | existing endpoint filter by status |
| **last activity timestamp** | 最后一次 step 真活动 | existing endpoint created_at / updated_at MAX |
| **lag** | 距上次 cron run / activity 多久；超阈值红色 [⚠️ stale] | `t_scheduled_task_run.finished_at` (cron) 或 last activity 时间差 |
| **recent error** | 最近 24h 有无 failure / outcome=error | existing endpoint filter outcome=error / status=failed |
| **today aggregate** | 今天处理总数 + 分类（promoted / noise / pending） | existing endpoint count(*) WHERE created_at>today (按 status group) |

### N3 健康颜色 encoding

- 🟢 **healthy**: lag < 2× cron interval AND no recent error
- 🟡 **warn**: lag 2-3× cron interval OR last 24h 有 1-2 error
- 🔴 **stale / unhealthy**: lag > 3× cron interval OR ≥3 recent error OR 0 today activity 且历史有 activity
- ⚪ **dormant**: 永远 disabled（V87 canary）
- ⚫ **empty**: 该 surface 该 step 历史就 0 activity（"prompt 这一列从来没真活过"，operator 一眼能看出来）

### N4 顶部 surface tab 重新设计

原 spec 是 3 列并列 swim-lane。重新评估：3 列并列 9+ step 在窄屏太挤，且 prompt / behavior_rule 列大概率全空 → 改为：

- 顶部 tab: **skill** / **prompt** / **behavior_rule**（默认 skill，localStorage 持久化）
- 一次显示 1 个 surface 的完整 timeline，纵向布局，每 step 一行 card
- step card 横向铺开 4 维 observability metric

操作上节点 vs 操作下节点：tab 控制下游 useQuery `?surface=skill|prompt|behavior_rule` filter（如 endpoint 支持）。

### N5 panel 内部只读（与原 spec 一致，强化）

- **不加任何操作按钮**（不 Run Manually / 不 Approve / 不 Trigger A/B）
- USER gate 节点的 `[PEND N]` chip 点击只能 drill-down 到现有 operate page（如 `/insights/optimization-events?stage=proposal_pending`）
- observability ≠ ops console。后续需要 ops 能力再单独立项

### N6 BE 改动评估（前置）

原 spec 承诺「0 BE 改动」。新 observability 维度核对：

| 维度 | 0 BE 可达? |
|---|---|
| in-flight count | ✅ existing filter |
| last activity timestamp | ✅ existing endpoint |
| lag (cron) | ✅ `/api/system-agents/monitor` 已有 lastRun（V7 加） |
| lag (non-cron) | ✅ FE 算 (now - lastActivity) |
| recent error | ✅ existing endpoint filter outcome=error |
| today aggregate count | 🟡 **可能需要新 endpoint** OR FE 拉 24h list 自算 |

Phase 1.0 决策点：先按「FE 拉 24h list 自算」做，如果某 endpoint 24h 范围太大（>500 行）FE 性能差，再加 BE aggregate endpoint。**默认 0 BE，FE-Dev 取证发现某 step 不可行再 flag NEEDS_CONTEXT 升级**。

### N7 第二层 USER 双 Tab（agentType × surface）

原 R1 加 agentType tab；N4 加 surface tab。两个 tab 并存：
- 第一层 tab: **User Agents** / **System Agents**（顶部，跟 V7 同款）
- 第二层 tab: **skill** / **prompt** / **behavior_rule**（在 User/System tab 内部，记 separate localStorage key per first-tab）

---

## 2026-05-18 启动前 ratify (3 处 refine)

启动前补 3 处 refine 体现 V6/V7 land 之后的现状变动 (详见 index.md "2026-05-18 启动前 ratify" section):

- **R1 Tab UX 一致性**: F3 FlywheelStatusPanel 顶部 widget 改 user/system agent Tabs (跟 Phase 2 同款), 不用 agentId Select. localStorage `flywheel.active_tab` 持久化. tab 控制下游所有 useQuery 加 `?agentType=user|system` filter (复用 V7 Phase 2 visibility fix BE endpoint pattern).
- **R2 Canary 状态 disabled marker**: F2 FlywheelTimeline 对 step ⑦⑧⑨ 加 disabled-state encoding — 灰色 + `<Tag>disabled (V87)</Tag>` 而非 "0 in-flight". 鼠标 hover 显 "V6 V87 暂停 metrics-collector cron, 此 step dormant". 解锁条件: 未来 V8.X 重启 canary cron 时移除 disabled marker.
- **R3 drill-down query param 真活验证 (Phase 1.0 必须)**: F1 数据源 mapping 9 step × 5 drill-down URL, 每个 target page (Patterns / OptimizationEvents / Canary / etc.) **必须 Phase 1.0 grep 真活验证** 是否真消费 `?stage=` `?surface=` `?agentId=` query filter. W2 已知 footgun (SystemAgentMonitorCard "View Sessions" 跳 ?agentId=N 但 SessionList 没消费, 跟 Schedules ?taskId 同款). Phase 1.0 取证报告必须列每个 URL 真消费 / 不消费 + 不消费的 fix path (补 useSearchParams or 调 link 形态).

## 摘要

Dashboard 加 `/insights/flywheel` tab（Insights 第 5 tab，跟 OptimizationEvents / BehaviorRuleEvolution / DynamicSim 同构 (B′) embed pattern），展示完整 9 步飞轮 + 3 surface 当前状态 + drill-down 跳现有 page。

## 用户流程

1. operator 打开 Insights → Flywheel tab
2. 顶部 agent 选择器（默认显示所有 agent 聚合，或选具体 agent）
3. 中部三列 swim-lane: skill / prompt / behavior_rule，每条 surface 一列
4. 每列纵向 9 个 step bar，每个 step 显示:
   - **count** in-flight (当前在该 step 的 candidate 数)
   - **last activity timestamp**
   - **last error message** (如有 failed / rolled_back)
5. 点 step bar → 跳对应 detail page (例如 ② 聚类 跳 `/insights/patterns`, ③ 归因 跳 `/insights/optimization-events?stage=proposal_pending`, ...)
6. 底部最近 24h activity feed (timeline 时间倒序 N 条最近事件)

## 功能需求

### F1. 飞轮 9 step state aggregation BE

**不新建 BE endpoint**，FE 多 useQuery 并行拉:

| step | 数据源 endpoint | filter |
|---|---|---|
| ① 标注 | `GET /api/sessions?annotated=true&agentId=` | session count + last annotated_at |
| ② 聚类 | `GET /api/insights/patterns?agentId=&status=open` | pattern count + recent ones |
| ③ 归因 | `GET /api/attribution/events?stage=proposal_pending&agentId=` | proposal count per stage |
| ④ candidate | `GET /api/agents/{id}/skill-evolution` (合并 prompt / behavior_rule) | candidate count per stage |
| ⑤ A/B | `GET /api/skill-ab-runs?agentId=&status=running` | running A/B count |
| ⑥ Gate | (无独立 endpoint，从 stage='ab_passed' 推断) | pending publish count |
| ⑦ 灰度 | `GET /api/canary/rollouts?agentId=&stage=canary` | active canary count |
| ⑧ 回流 | embed in canary endpoint（已含 metrics） | last bucket_at |
| ⑨ 决策 | rollout_stage='production'/'rolled_back' | recent promotions / rollbacks count |

FE 端聚合 → 渲染 swim-lane。

### F2. FlywheelTimeline component

Per surface (skill / prompt / behavior_rule):
- 9 step bar 纵向布局
- 每 bar: { stepName, count, lastUpdated, hasError, errorMsg? }
- 颜色编码: in-flight (蓝) / done (绿) / failed (红) / pending (灰)
- 点 bar 跳现有 detail page

### F3. FlywheelStatusPanel (主 panel)

- 顶部 agent 选择器（option `all` / specific agent）
- 中部 3 列 FlywheelTimeline (skill / prompt / behavior_rule)
- 底部 24h activity feed (top 20 事件，时间倒序)
- 右上角 manual refresh 按钮（不做自动 polling，operator 手动控）

### F4. Insights.tsx 加 Flywheel tab

- INSIGHTS_TABS 加 'flywheel'
- activeTab handler 加 case → `<FlywheelStatusPage />`
- ~15 行 surgical change (B′ pattern)

### F5. (可选) FE config: 收起 / 展开 surface 列

operator 只关心 skill surface 时可隐藏 prompt / behavior_rule 列。localStorage 持久化。

## 非目标

- 不新建 BE endpoint (复用现有 V1-V5 endpoint)
- 不做实时刷新 (推 V5.5 DYNAMIC-SIM-LIVE-TRANSCRIPT 同款 WS broadcast 整体方案)
- 不做历史回放 / 趋势图
- 不做 cross-agent KPI 聚合
- 不动核心 7+1 BE + 3 FE 文件 (Iron Law)
- 不引入新 LLM / 新 schema

## 验收标准

- [ ] 新建 `pages/FlywheelStatus.tsx` + `components/flywheel/FlywheelTimeline.tsx` + `components/flywheel/FlywheelStatusPanel.tsx`
- [ ] Insights.tsx 加 'flywheel' tab + ~15 行
- [ ] 跨 5 endpoint 多 useQuery 并行拉数据 + FE aggregation
- [ ] 9 step swim-lane 渲染 + 颜色编码 + count + last timestamp
- [ ] 点 step bar 跳现有 detail page (5 个 drill-down link)
- [ ] 24h activity feed (top 20 events)
- [ ] FE tsc + npm build EXIT=0
- [ ] Iron Law 核心 3 FE 文件 git diff = 0
- [ ] BE 不动 (0 改动)
- [ ] 测试: FlywheelStatusPanel.test.tsx 1-2 case 锁基本渲染 + drill-down 跳路径

## 后续 backlog

- WS / SSE 实时刷新（跟 V5.5 DYNAMIC-SIM-LIVE-TRANSCRIPT 一起做）
- 历史趋势图（按 time-bucket aggregate）
- Cross-agent KPI dashboard（"全部 agent 上周提了多少 proposal / 通过多少"）
