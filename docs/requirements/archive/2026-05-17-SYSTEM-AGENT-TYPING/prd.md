# SYSTEM-AGENT-TYPING PRD

---
id: SYSTEM-AGENT-TYPING
status: design-draft
owner: youren
priority: P1
risk: Mid
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
updated: 2026-05-17
---

## 摘要

`t_agent` 加 `agent_type` enum 'user' / 'system' 字段 + V89 migration 显式标 5 个已知 system agent + session-annotator user agent outcome coverage 修复（Phase 1）+ AgentList FE 默认隐藏 + AgentDrawer 锁定 system agent 关键字段 + 集中观察面板 `/insights/system-agents`（Phase 2 后续 PR）。

## 用户流程

### 流程 A: 日常 dashboard 使用 (AgentList)

1. 用户打开 `/agents` → 默认仅显示 agent_type='user' 的 5 个 agent (干净)
2. 顶部 "Show system agents" toggle (default off, localStorage 持久化)
3. 打开后列出 5 个 system agent，**视觉区分** (灰色 / 紫色 chip "System")
4. 点 system agent → AgentDrawer 进入只读模式 (但仍可看 system_prompt / tool_ids / 调度信息)

### 流程 B: 看 system agent 活动 (新 observability page)

1. 用户打开 `/insights/system-agents`
2. 5 个 system agent 一行卡片显示:
   - name + description
   - cron schedule (`0 30 * * * *` 等)
   - last run timestamp + success / failure
   - 7d 触发计数
   - 7d 产出实体计数 (annotations / proposals / metric snapshots / consolidations / trials)
   - "Run Manually" 按钮 (跳 ScheduledTask manual trigger，admin 可发)
   - "View Recent Sessions" 链接跳 `/sessions?agentId={id}&origin=*`

### 流程 C: 误编辑保护

1. 用户从 AgentList toggle on 后点 attribution-curator
2. AgentDrawer 顶部 banner: "⚠️ System agent — managed by V81 bootstrap. Edits will be overwritten on next server restart."
3. 大部分字段 read-only (name / model_id / system_prompt / tool_ids)
4. 仅 `status` toggle (enabled/disabled) 可改 (allow user 临时 disable cron)
5. 删除按钮 disabled + tooltip "System agents cannot be deleted; disable instead"

## 功能需求

### F1. 数据模型: t_agent.agent_type （Phase 1）

**V89 migration**（V87 已被 V87__disable_canary_metrics_collector.sql 占，V88 已被 V88__add_candidate_uuid_sidecar_columns.sql 占）:
```sql
ALTER TABLE t_agent ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'user';
ALTER TABLE t_agent ADD CONSTRAINT chk_agent_type CHECK (agent_type IN ('user', 'system'));

-- 显式 mark 5 个已知 system agent
UPDATE t_agent SET agent_type='system' WHERE name IN (
    'memory-curator', 'session-annotator', 'metrics-collector',
    'attribution-curator', 'user-simulator'
);
```

**AgentEntity**:
- 加 `String agentType` 字段 + getter/setter（`AgentController` 直接返 AgentEntity，Jackson 自动序列化，**无需新 DTO** — 来自 2026-05-17 be-dev Phase 1.0 取证）
- Bootstrap (UserSimulatorBootstrap / AttributionCuratorBootstrap / etc.) 设 agentType='system' (idempotent update path 加在 findFirstByName 之后、prompt-swap 短路 return 之前)

### F2. FE AgentList filter + visual badge

- 顶部加 toggle `Show system agents` (default off)
- localStorage `agentlist.show_system_agents` 持久化
- system agent 渲染时加灰色背景 + chip `<Tag color="purple">System</Tag>`
- list query 加 filter param `?agentType=user|system|all`
- BE endpoint `GET /api/agents` 支持 `agentType` filter

### F3. AgentDrawer 锁定 system agent

- AgentDrawer 检测 agent.agentType='system' 时:
  - 顶部 Banner: "⚠️ System agent..."
  - name / model_id / system_prompt / tool_ids / behavior_rules / lifecycle_hooks 等关键字段 read-only (用 Form readOnly prop)
  - status toggle 仍可改
  - "Delete" 按钮 disabled + tooltip
- "Save" 按钮如果 user 改了任何 read-only 字段则 disabled + 提示

### F4. /insights/system-agents 监控面板

新建 `pages/SystemAgents.tsx` + `components/systemAgents/SystemAgentMonitorCard.tsx`:
- 顶部 title "System Agents"
- 5 个 system agent 一行卡片，每卡片 6 字段:
  - name + description
  - cron schedule (从 t_scheduled_task lookup by agent_id)
  - last_run_at + last_run_status (cron / scheduled task run)
  - 7d trigger count
  - 7d output count (per surface: annotations / proposals / metric snapshots / consolidations / trials)
  - 操作按钮: "Run Manually" + "View Sessions" + "View Schedule"
- (可选) Insights tab embed 第 6 tab `'system-agents'` 与 BehaviorRuleEvolution / DynamicSim 同构

### F5. BE endpoint (复用 + 新加)

- 复用现有 `GET /api/agents?agentType=user` (改加 agentType filter)
- 新加 `GET /api/system-agents/monitor` 返 5 个 system agent + 聚合 metrics (per-agent 7d trigger/output count)
- 不新加 entity / table

### F6. Chat page gate (Phase 2 后续)

- Chat page (`/chat/:sessionId?`) 检测 selected agent.agentType='system' 时:
  - Send button disabled
  - Banner: "System agents are read-only via Chat. Use admin tools to configure."
- 但**read 完整** (transcripts / tool_use blocks / etc.) 都正常显示

### F7. session-annotator user agent 覆盖修复 (Phase 1 — 飞轮 layer 1 root cause)

**问题陈述** (2026-05-17 DB SQL 取证):
- 268 production session, 117 outcome 标注**全在 system agent** (attribution-curator 63 / metrics-collector 24 / session-annotator 29 / memory-curator 1)
- user agent (Main Assistant 58 + Design 23 + Research 15 + Code 14 = 110 session) **0 个 outcome 标注**
- session-annotator system prompt + `SessionAnnotationSignalService` 代码 grep 都没显式 `is_public` / agent 过滤，但实际效果是 user agent 完全没标 → 真 root cause 在 STEP 1 DetectSignalAnnotations 内部 list 逻辑 或 STEP 2 LLM `cap=10` 优先级

**3 hypothesis (BE-Dev systematic-debugging Phase 1 取证选一)**:
- **A**: `DetectSignalAnnotations.detectAndPersist` 内部 list 排除 user agent
- **B**: STEP 2 LLM `cap=10` 优先 system agent（因为 system agent failure 更频繁先被 LLM 看到，user agent 永远到不了 list 前 10）
- **C**: user agent session signal 太弱（LLM_CALL span error / tool_failure / agent_error 不够）

**修复方向（取决于取证）**:
- 若 A → 删 list 逻辑里的 filter，全 production session 都进 candidate list
- 若 B → 改 cap=10 排序逻辑，优先 user agent (priority 1) + system agent (priority 2)
- 若 C → 改 system prompt STEP 1 让 LLM 显式提到 user agent session 也要 annotate（兜底），不指望 deterministic signal

**不改**:
- `SessionAnnotationLlmService` 的 DECISION HEURISTICS（outcome / suspect_surface enum 不变）
- annotation_value 5 值 (success / partial_success / failure / cancelled / unclear)

**验收**:
- 真活：BE 重启 + 手动触发 `session-annotator-hourly` 跑一轮，psql 验证 `t_session_annotation` 表里 `Main Assistant` agent_id outcome 标注 >0
- IT: `SessionAnnotationSignalServiceUserAgentCoverageIT` — mock 3 个 user agent session, 跑 detectAndPersist → 验证 sessions_needing_llm 列表包含它们

## 非目标

- 不重命名 / 删除已有 agent
- 不动 Bootstrap idempotent 逻辑
- 不破 V1-V5 cron 路径
- 不动 Iron Law 核心 7+1 文件
- 不新加 schema 表 (只加 1 列)
- 不引入 cross-tenant / RBAC (那是 SECURITY-ADMIN-RBAC backlog 项)

## 验收标准

### Phase 1 — 本次 PR（必须全绿才 commit）

**代码**
- [ ] **V89** migration 加 t_agent.agent_type column + UPDATE 5 个 system agent
- [ ] AgentEntity 加 agentType + getter/setter（Jackson 自动序列化无需新 DTO）
- [ ] 5 个 Bootstrap class 在 idempotent update 时设 agentType='system' (启动自愈)
- [ ] F7: BE-Dev systematic-debugging Phase 1 取证报告（A/B/C 哪个 true + 修复方案）
- [ ] F7: 修对应 service / system prompt 让 user agent 进 sessions_needing_llm 列表
- [ ] FE `schemas.ts` AgentSchema 加 agentType field（防 zod silent strip）

**测试**
- [ ] V89 migration IT: agent_type 字段在 + 5 个 system agent 标对
- [ ] AgentEntity.agentType getter/setter + AgentRepository.findByAgentType 测试
- [ ] SessionAnnotationSignalServiceUserAgentCoverageIT (F7 IT)

**验证**
- [ ] mvn -pl skillforge-server -am test → BUILD SUCCESS（无 regression）
- [ ] tsc + npm build EXIT=0
- [ ] Iron Law 核心 7+1 + 3 FE 文件 git diff = 0
- [ ] 真活：BE 重启 + 手动触发 session-annotator-hourly cron 一轮，psql 查 user agent outcome 标注 >0

### Phase 2 — 后续 PR（不阻塞本次）

- [ ] BE `GET /api/agents` 接 agentType filter (user/system/all default user)
- [ ] BE 新 `GET /api/system-agents/monitor` endpoint + DTO
- [ ] FE AgentList 加 Show system agents toggle + 视觉 badge
- [ ] FE AgentDrawer 检测 system 时 read-only 关键字段 + banner + delete disabled
- [ ] FE Chat page 检测 system agent 时 send disabled + banner
- [ ] FE pages/SystemAgents.tsx + 监控卡片
- [ ] FE (可选) Insights.tsx 加 'system-agents' 第 6 tab
- [ ] AgentControllerTest 加 agentType filter case
- [ ] FE AgentList.test.tsx + Chat.test.tsx + SystemAgents.test.tsx
- [ ] dashboard 真启动: AgentList default 只显示 5 个 user agent + toggle 出 system / Insights system-agents tab 显示 cron 调度 + manual run 可触发

## 后续 backlog

- SECURITY-ADMIN-RBAC (RBAC for system agent 更细的 admin permissions)
- 跨 system agent 协作 visualization (system agent A 调 system agent B 时 trace tree)
