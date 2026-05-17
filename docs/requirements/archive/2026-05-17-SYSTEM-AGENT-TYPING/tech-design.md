# SYSTEM-AGENT-TYPING 技术方案

---
id: SYSTEM-AGENT-TYPING
status: design-draft
prd: ./prd.md
risk: Mid
mode: full
created: 2026-05-16
updated: 2026-05-17
---

## TL;DR

**Phase 1 (本次 PR, Full ~1-1.5d)**: t_agent 加 1 列 (V89) + 5 Bootstrap 一行 update + FE Zod schema 加字段 + session-annotator user agent 覆盖修复 (BE-Dev 先 systematic-debugging 取证 3 hypothesis 再 fix)。**核心目标：飞轮 layer 1 root cause = user agent failure 进飞轮**

**Phase 2 (独立后续 PR, Mid ~2-3d)**: 3 FE 改动 (AgentList toggle / AgentDrawer 锁 / Chat send 禁) + 1 新 page (SystemAgents 监控) + BE 新 endpoint。UX 增强不阻塞 Phase 1 落地。

## 现状证据 (2026-05-16 grep)

### t_agent schema 现状

```sql
-- 现有字段 (V1 init + 各 V*) 含:
id BIGINT PK
name VARCHAR(64)
description TEXT
system_prompt TEXT
model_id TEXT
tool_ids TEXT     -- JSON array string
soul_prompt TEXT
owner_id BIGINT   -- nullable! V69 memory-curator owner_id=NULL
is_public BOOLEAN
status VARCHAR(16)
behavior_rules TEXT
lifecycle_hooks TEXT
mcp_server_ids TEXT
... (~28 columns)
```

**关键发现**:
- owner_id 不可靠区分 system vs user (V1-V5 system agent 多用 owner_id=1)
- is_public 不可靠 (user 也可创公开 agent)
- 没有 agent_type / is_system 字段

### 5 个 system agent (per migration history)

| ID | Name | Migration | Bootstrap |
|---|---|---|---|
| 6 | memory-curator | V68/V69 | MemoryCuratorBootstrap |
| 7 | session-annotator | V75 | SessionAnnotatorBootstrap |
| 8 | metrics-collector | V79 | MetricsCollectorBootstrap |
| 9 | attribution-curator | V81 | AttributionCuratorBootstrap |
| 10 | user-simulator | V85 | UserSimulatorBootstrap |

(grep `SEE_FILE\|bootstrap` 实际确认每个 bootstrap 类名)

### FE AgentList 现状

`pages/AgentList.tsx`:
- query `GET /api/agents` (`getAgents(userId)`) 拉所有 agent
- render: card grid，无 filter

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 加新字段 vs 复用 owner_id / is_public | **加新字段 `agent_type` enum** | owner_id 不可靠 (V69 NULL / V75-85 用 1); is_public 也不可靠 (user 可创公开 agent); 显式 typing 最清晰 |
| 默认值 | **'user' DEFAULT** | 大部分 agent 是 user 创建; 加 V89 UPDATE 5 个已知 system agent |
| FE filter 形态 | **AgentList 顶部 toggle + agentType=user 默认** | 用户日常看的是自己 agent; toggle 是 progressive disclosure |
| Edit 保护 | **关键字段 read-only + Banner 警告** | 比完全 lock 更友好; status toggle 仍允许临时 disable |
| Chat send gate | **检测 agentType='system' 直接 disable send button + banner** | 用户读完整 OK; send 禁止防破系统 |
| 监控面板位置 | **新建 pages/SystemAgents.tsx + (可选) Insights 6th tab** | 主 page 干净；如果嵌 Insights 则跟 OptimizationEvents / BehaviorRuleEvolution / DynamicSim 同构 |
| BE 新 endpoint | **复用 GET /api/agents?agentType= + 新 GET /api/system-agents/monitor** | monitor endpoint 跨表聚合 (t_agent + t_scheduled_task + 各产出表) 不在普通 agent endpoint 里 |
| 5 Bootstrap update | **idempotent path 加 setAgentType('system')** | 启动时自愈, 防 V89 UPDATE 漏掉某 agent |

## 数据模型

### V89 migration

> V87 已被 `V87__disable_canary_metrics_collector.sql`（V6 FLYWHEEL-LOOP-CLOSURE）占, V88 已被 `V88__add_candidate_uuid_sidecar_columns.sql`（V6 同包）占。本包用 V89。

```sql
-- V89__add_agent_type.sql

-- 1) 加 column + CHECK
ALTER TABLE t_agent ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'user';
ALTER TABLE t_agent ADD CONSTRAINT chk_agent_type CHECK (agent_type IN ('user', 'system'));

-- 2) 显式 mark 5 个已知 system agent
UPDATE t_agent SET agent_type='system'
WHERE name IN (
    'memory-curator',
    'session-annotator',
    'metrics-collector',
    'attribution-curator',
    'user-simulator'
);
```

### AgentEntity.java 加字段

```java
@Column(nullable = false, length = 16)
private String agentType = "user";  // 'user' | 'system'

public String getAgentType() { return agentType; }
public void setAgentType(String agentType) { this.agentType = agentType; }
```

### 5 Bootstrap idempotent path 加 setAgentType

例如 UserSimulatorBootstrap:
```java
if (existing == null) {
    AgentEntity newAgent = new AgentEntity();
    // ... 现有 fields
    newAgent.setAgentType("system");  // ← 新加
    agentRepository.save(newAgent);
} else {
    // idempotent update path
    existing.setSystemPrompt(loadedPrompt);
    // ... 现有 fields
    if (!"system".equals(existing.getAgentType())) {
        existing.setAgentType("system");  // ← 启动自愈
    }
    agentRepository.save(existing);
}
```

## 服务层 + Controller

### AgentController 加 agentType filter

```java
@GetMapping("/agents")
public ResponseEntity<List<AgentResponse>> listAgents(
        @RequestParam Long userId,
        @RequestParam(required = false, defaultValue = "user") String agentType) {
    List<AgentEntity> agents;
    if ("all".equals(agentType)) {
        agents = agentRepository.findAllVisibleToUser(userId);  // existing
    } else {
        // 加新 repo method or filter in service
        agents = agentRepository.findByAgentTypeAndVisible(agentType, userId);
    }
    return ResponseEntity.ok(agents.stream().map(AgentResponse::from).toList());
}
```

### SystemAgentMonitorController (新)

```java
@RestController
@RequestMapping("/api/system-agents")
public class SystemAgentMonitorController {

    @GetMapping("/monitor")
    public ResponseEntity<List<SystemAgentMonitorResponse>> monitorAll() {
        List<AgentEntity> systemAgents = agentRepository.findByAgentType("system");
        List<SystemAgentMonitorResponse> result = new ArrayList<>();
        for (AgentEntity agent : systemAgents) {
            // join t_scheduled_task by agent_id → cron / last_run_at
            // join 各产出表 (t_session_annotation / t_optimization_event / t_canary_metric_snapshot / t_memory / t_simulator_trial)
            // 计算 7d trigger count + 7d output count
            result.add(SystemAgentMonitorResponse.from(agent, ...));
        }
        return ResponseEntity.ok(result);
    }
}

// DTO
public record SystemAgentMonitorResponse(
    Long agentId,
    String name,
    String description,
    String cronSchedule,     // null if no scheduled task
    Instant lastRunAt,
    String lastRunStatus,    // 'success' | 'failed' | null
    long sevenDayTriggerCount,
    long sevenDayOutputCount,
    String outputEntityType  // 'annotations' | 'proposals' | 'metrics' | 'consolidations' | 'trials'
) { ... }
```

## FE 改动

### AgentList.tsx

加 toggle + filter:

```tsx
const [showSystemAgents, setShowSystemAgents] = useLocalStorageBoolean(
    'agentlist.show_system_agents', false);

const agentType = showSystemAgents ? 'all' : 'user';
const agentsQuery = useQuery({
    queryKey: ['agents', userId, agentType],
    queryFn: () => getAgents(userId, agentType),
});
```

每个 agent card 渲染时:
```tsx
<Card>
    {agent.agentType === 'system' && (
        <Tag color="purple" style={{ position: 'absolute', top: 8, right: 8 }}>System</Tag>
    )}
    {/* ... */}
</Card>
```

### AgentDrawer.tsx

```tsx
const isSystemAgent = agent.agentType === 'system';

return (
    <Drawer>
        {isSystemAgent && (
            <Alert
                type="warning"
                message="⚠️ System agent — managed by V81/V85/V87 bootstrap"
                description="Edits to system_prompt / tool_ids / behavior_rules will be overwritten on next server restart. To stop the cron, toggle status."
            />
        )}
        <Form>
            <Form.Item label="Name">
                <Input readOnly={isSystemAgent} {...field} />
            </Form.Item>
            {/* ... */}
        </Form>
        <Button danger disabled={isSystemAgent} title={isSystemAgent ? "System agents cannot be deleted; disable instead" : ""}>
            Delete
        </Button>
    </Drawer>
);
```

### Chat.tsx

```tsx
const isSystemAgent = activeAgent?.agentType === 'system';

return (
    <div>
        {isSystemAgent && (
            <Alert type="info" message="System agent — read-only. Configure via admin tools." />
        )}
        <ChatWindow>
            <textarea disabled={isSystemAgent} />
            <Button type="primary" disabled={isSystemAgent} onClick={onSend}>Send</Button>
        </ChatWindow>
    </div>
);
```

### pages/SystemAgents.tsx (新)

```tsx
export default function SystemAgents() {
    const monitorQuery = useQuery({
        queryKey: ['system-agents', 'monitor'],
        queryFn: () => getSystemAgentMonitor(),
    });

    return (
        <div>
            <h1>System Agents</h1>
            <Row gutter={[16, 16]}>
                {monitorQuery.data?.map(agent => (
                    <Col span={24} key={agent.agentId}>
                        <SystemAgentMonitorCard data={agent} />
                    </Col>
                ))}
            </Row>
        </div>
    );
}
```

### Insights.tsx 加第 6 tab (可选)

```tsx
INSIGHTS_TABS.push({ key: 'system-agents', label: 'System Agents' });
// activeTab handler 加 case → <SystemAgentsPage />
```

## 实施计划

### Phase 1.0 — 证伪 + 红测试 (0.5 天) ✅ DONE 2026-05-17

- grep 5 Bootstrap class 真实文件名 + idempotent path 实际位置（5 个都确认）
- grep AgentEntity 现有 fields 防 V89 字段名冲突（28 列, 无冲突）
- **AgentResponse DTO 不存在** — AgentController 直接返 AgentEntity, Jackson 自动序列化, Phase 1.1 简化为 entity + bootstrap 无 Controller 改动
- F7 systematic-debugging 3 hypothesis 取证 → **B 确认（findRecentByLimit cap*3=30 createdAt DESC 全 system + LinkedHashMap first cap=10 全 system → user agent starved）**
- 红测试 (real-run fail evidence):
  - `SessionAnnotationSignalServiceUserAgentCoverageTest` (Mockito unit) → "Expecting actual: 0L to be greater than or equal to: 1L" FAIL ✓
  - `AgentTypeMigrationIT` (gated `-Dskillforge.runMigrationIT=true`, 同 EvalTaskMigrationIT 型) — Docker 起后 red

### Phase 1.1 — BE: V89 migration + Entity + 5 Bootstrap (0.5-1 天)

- `V89__add_agent_type.sql` migration
- AgentEntity 加 agentType + getter/setter（无需新 DTO）
- 5 Bootstrap (`MemoryCuratorBootstrap` / `SessionAnnotatorBootstrap` / `MetricsCollectorBootstrap` / `AttributionCuratorBootstrap` / `UserSimulatorBootstrap`) idempotent update path 加 `setAgentType("system")`（放在 findFirstByName 之后、prompt-swap 短路 return 之前 — 启动自愈跟 prompt-swap 解耦）
- BE tests: AgentEntityTest + AgentRepository.findByAgentType + 5 Bootstrap idempotent setAgentType test

### Phase 1.2 — BE: session-annotator user agent coverage 修复 (0.5 天)

- 基于 Phase 1.0 取证（**Hypothesis B 确认 starvation**），实施 **选项 R1** = repo 层加 JOIN 查询:
  - `SessionAnnotationRepository.findRecentByAgentType(source, agentType, limit)` JPQL JOIN t_session + t_agent 过滤 agent_type
  - `SessionAnnotationSignalService.findSessionsNeedingLlmAnnotation`：调 2 次（user signals 全收 + system signals 填充到 capped）
- 跑 `SessionAnnotationSignalServiceUserAgentCoverageTest` 必须由 red → green
- 不改 `SessionAnnotationLlmService.DECISION HEURISTICS` (outcome/suspect_surface enum 不变)

### Phase 1.2 — FE: AgentList toggle + AgentDrawer 锁 + Chat gate (1 天)

- AgentList toggle + filter + visual badge
- AgentDrawer banner + readOnly props + delete disabled
- Chat page send button gate
- FE tests: AgentList.test / AgentDrawer.test / Chat.test

### Phase 1.3 — FE: SystemAgents page + tab embed (0.5-1 天)

- pages/SystemAgents.tsx
- components/systemAgents/SystemAgentMonitorCard.tsx
- api/systemAgents.ts wrapper
- (可选) Insights 6th tab
- FE tests: SystemAgents.test

### Phase 1.5 — e2e (0.5 天)

- 启 server + dashboard 真访问
- AgentList default 5 user agent / toggle 出 5 system agent
- AgentDrawer system agent banner + readonly fields
- Chat system agent send disabled
- SystemAgents page 显示 5 cards

### Phase Final — 归档 (0.3 天)

- requirements active → archive
- delivery-index.md + todo.md / README.md 同步

## 风险与边界

### Low Risk
- V89 ALTER + UPDATE 在小表 t_agent (~10 rows) 上瞬秒完成
- agent_type 默认 'user' 向后兼容现有 user agent
- Bootstrap idempotent update 保 server restart 自愈

### Mid Risk
- 5 Bootstrap class 散在 5 个 V69/V75/V79/V81/V85 实施 commit 里，需 grep 找对每个的 idempotent path
- AgentDrawer readOnly 实施时不要破现有 form validation (Phase 1.2 grep 现有 form 实现)

## Iron Law 全程守住

- 核心 7+1 BE 文件 + 核心 3 FE 文件 git diff = 0
- t_agent 加 column 不动 PK / index / FK (低风险 schema change)
- 不动 Bootstrap idempotent 逻辑 (只在 update path 加一行 setAgentType)
- 不动 Chat 主路径 (只加 send button disabled 检查)

## 测试计划

- BE: V89 migration IT + SessionAnnotationSignalServiceUserAgentCoverageTest (Phase 1) / AgentControllerTest agentType filter + SystemAgentMonitorControllerTest aggregation (Phase 2 后续)
- FE: AgentList.test (toggle / badge) + AgentDrawer.test (system readonly) + Chat.test (send disabled) + SystemAgents.test (cards 渲染)
- e2e manual: dashboard 真访问 + 5 个 system agent 真可看 / 真不可改

## 评审记录

- 2026-05-16 创建 design-draft (基于 user "system agent 跟 user agent 应该区分 + system agent 不能对话但能看" 反馈)
