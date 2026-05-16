# SYSTEM-AGENT-TYPING PRD

---
id: SYSTEM-AGENT-TYPING
status: design-draft
owner: youren
priority: P2
risk: Low
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-05-16
---

## 摘要

`t_agent` 加 `agent_type` enum 'user' / 'system' 字段 + V87 migration 显式标 5 个已知 system agent + AgentList FE 默认隐藏 + AgentDrawer 锁定 system agent 关键字段 + 集中观察面板 `/insights/system-agents`。

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

### F1. 数据模型: t_agent.agent_type

**V87 migration**:
```sql
ALTER TABLE t_agent ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'user';
ALTER TABLE t_agent ADD CONSTRAINT chk_agent_type CHECK (agent_type IN ('user', 'system'));

-- 显式 mark 5 个已知 system agent
UPDATE t_agent SET agent_type='system' WHERE name IN (
    'memory-curator', 'session-annotator', 'metrics-collector',
    'attribution-curator', 'user-simulator'
);
```

**AgentEntity / DTO**:
- 加 `String agentType` 字段 + getter/setter
- API response `AgentResponse` 加 agentType
- Bootstrap (UserSimulatorBootstrap / AttributionCuratorBootstrap / etc.) 设 agentType='system' (idempotent update)

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

### F6. Chat page gate

- Chat page (`/chat/:sessionId?`) 检测 selected agent.agentType='system' 时:
  - Send button disabled
  - Banner: "System agents are read-only via Chat. Use admin tools to configure."
- 但**read 完整** (transcripts / tool_use blocks / etc.) 都正常显示

## 非目标

- 不重命名 / 删除已有 agent
- 不动 Bootstrap idempotent 逻辑
- 不破 V1-V5 cron 路径
- 不动 Iron Law 核心 7+1 文件
- 不新加 schema 表 (只加 1 列)
- 不引入 cross-tenant / RBAC (那是 SECURITY-ADMIN-RBAC backlog 项)

## 验收标准

### 代码
- [ ] V87 migration 加 t_agent.agent_type column + UPDATE 5 个 system agent
- [ ] AgentEntity / AgentResponse 加 agentType + serializer
- [ ] 5 个 Bootstrap class 在 idempotent update 时设 agentType='system'
- [ ] BE `GET /api/agents` 接 agentType filter (user/system/all default user)
- [ ] BE 新 `GET /api/system-agents/monitor` endpoint + DTO
- [ ] FE AgentList 加 Show system agents toggle + 视觉 badge
- [ ] FE AgentDrawer 检测 system 时 read-only 关键字段 + banner + delete disabled
- [ ] FE Chat page 检测 system agent 时 send disabled + banner
- [ ] FE pages/SystemAgents.tsx + 监控卡片
- [ ] FE (可选) Insights.tsx 加 'system-agents' 第 6 tab

### 测试
- [ ] V87 migration 跑过 + agent_type 字段在 + 5 个 system agent 标对
- [ ] AgentControllerTest 加 agentType filter case
- [ ] FE AgentList.test.tsx 加 toggle / badge case
- [ ] FE Chat.test.tsx 加 system agent send disabled case
- [ ] FE SystemAgents.test.tsx 加监控卡片渲染

### 验证
- [ ] mvn -pl skillforge-server -am test → BUILD SUCCESS
- [ ] tsc + npm build EXIT=0
- [ ] Iron Law 核心 7+1 + 3 FE 文件 git diff = 0
- [ ] dashboard 真启动: AgentList default 只显示 5 个 user agent + toggle 出 system / Insights system-agents tab 显示 cron 调度 + manual run 可触发

## 后续 backlog

- SECURITY-ADMIN-RBAC (RBAC for system agent 更细的 admin permissions)
- 跨 system agent 协作 visualization (system agent A 调 system agent B 时 trace tree)
