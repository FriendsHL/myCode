# PROD-LABEL-CLUSTER 技术方案

---
id: PROD-LABEL-CLUSTER
status: ratified
mode: mid
created: 2026-05-14
updated: 2026-05-14
---

## 0. 现状证据（开工前必跑 Phase 1.0 证伪）

### 0.1 复用目标已存在 — 文件路径

| 复用项 | 文件路径 | 用法 |
|---|---|---|
| Signal-based 6 reason 检测 | `skillforge-server/src/main/java/com/skillforge/server/service/TraceScenarioImportService.java:136-151` | V1 Stage A 直接调，输出复制到 t_session_annotation |
| Trace 查询 | `skillforge-server/src/main/java/com/skillforge/server/repository/LlmTraceRepository.java` | 取 sessionId → trace 集 |
| Span 查询 | `skillforge-server/src/main/java/com/skillforge/server/repository/LlmSpanRepository.java` | 取 traceId → span 集（tool span 的 error/errorType） |
| Memory-curator agent 模板 | `skillforge-server/src/main/java/com/skillforge/server/memory/llmsynth/MemoryCuratorBootstrap.java:43`<br>`+ classpath:memory-curator-system-prompt.md`<br>`+ V69 Flyway seed t_agent` | V1 session-annotator agent 完全照抄此模板 |
| Memory-curator 工具模板 | `skillforge-server/src/main/java/com/skillforge/server/tool/memorysynth/{ClusterMemoriesTool,CreateMemoryProposalTool,ListActiveUsersTool,ListMemoryCandidatesTool}.java` | V1 session-annotator 的 SessionAnnotationWrite / SessionFetch 复制此 4 工具组织方式 |
| Memory synthesis scheduler | `skillforge-server/src/main/java/com/skillforge/server/memory/llmsynth/LlmMemorySynthesisScheduler.java` | V1 三个 hourly cron 照此模板 + advisory lock 模式 |
| SubAgent dispatch | `skillforge-server/src/main/java/com/skillforge/server/subagent/SubAgentRegistry.java`<br>`+ tool/SubAgentTool.java` | session-annotator 派发走它，**不开新 dispatch 路径** |
| Traces dashboard 详情页 | `skillforge-dashboard/src/components/.../Traces.tsx`（含 query param 深链接，OBS-2 已实现） | Pattern member 跳转目标，零改动 |
| EvalAnnotationEntity 模型形态 | `skillforge-server/src/main/java/com/skillforge/server/entity/EvalAnnotationEntity.java` | 人工修标 V3 复用，本包不动 |

### 0.2 不动的核心文件清单（grep diff 验证）

| 文件 | 不动理由 |
|---|---|
| `skillforge-server/.../entity/SessionEntity.java` | 触碰即 identity-column-on-rewrite + persistence-shape-invariant 双红灯 |
| `skillforge-server/.../entity/SessionMessageEntity.java` | 同上 |
| `skillforge-server/.../service/ChatService.java` | 核心文件 |
| `skillforge-server/.../service/SessionService.java` | 核心文件 |
| `skillforge-server/.../service/CompactionService.java` | 核心文件 |
| `skillforge-core/.../engine/AgentLoopEngine.java` | 核心文件 |
| `skillforge-server/.../service/TraceScenarioImportService.java` | 只**复用其内部 reason 检测方法**，不改其签名或行为 |

**Phase 1.0 证伪步骤**（dev 开工第一步必跑）：
1. 跑一遍 memory-curator 当前 dispatch 链路，确认 SubAgentRegistry 接 system agent 不需要 hack
2. 单元测试调用 `TraceScenarioImportService` 现有 reason 检测，确认输出格式可被直接 map 到 t_session_annotation
3. 写**红测试**：建一条假 session + 假 trace 带 tool error，跑 V1 signal stage，断言 t_session_annotation 出现 `tool_failure` 标签 → 此时实现还没写应该红
4. 然后才进 Phase 1.1 实现

---

## 1. 总体架构

```
                ┌─────────────────────────────────────┐
                │ Hourly cron 1: signal-annotation-cron  │
                │                                       │
                │ 扫 t_session 上 1h completed_at       │
                │  └→ 调 TraceScenarioImportService     │
                │       现有 reason 检测                 │
                │  └→ Upsert t_session_annotation (source=  │
                │       signal)                         │
                └─────────────────────────────────────┘
                                  │
                                  ▼
                ┌─────────────────────────────────────┐
                │ Hourly cron 2: llm-annotation-cron     │
                │                                       │
                │ 扫上 1h completed_at 且 signal 已跑   │
                │  └→ 按 user/agent 分组分批             │
                │  └→ 对每批派 session-annotator agent   │
                │       (SubAgentDispatch + 4 tool)    │
                │  └→ agent 输出 outcome/suspect_      │
                │     surface 写 t_session_annotation        │
                │       (source=llm)                    │
                └─────────────────────────────────────┘
                                  │
                                  ▼
                ┌─────────────────────────────────────┐
                │ Hourly cron 3: clustering-cron       │
                │                                       │
                │ 扫过去 7d 有新 label 的 session       │
                │  └→ bucket on (outcome,               │
                │      suspect_surface, top_failing_    │
                │      tool, agent_id)                  │
                │  └→ ≥3 member upsert t_session_      │
                │      pattern + member rows            │
                └─────────────────────────────────────┘
                                  │
                                  ▼
                ┌─────────────────────────────────────┐
                │ Dashboard /insights/patterns        │
                │                                       │
                │ GET /api/insights/patterns           │
                │ GET /api/insights/patterns/{id}/      │
                │     members                          │
                └─────────────────────────────────────┘
```

## 2. 数据库 Schema

### 2.1 Flyway migration `V72__create_session_annotation_and_pattern.sql`

```sql
CREATE TABLE t_session_annotation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL,
    annotation_type      VARCHAR(32) NOT NULL,
    annotation_value     VARCHAR(64) NOT NULL,
    source          VARCHAR(16) NOT NULL,  -- signal / llm / human
    confidence      DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    reasoning       TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_session_annotation UNIQUE (session_id, annotation_type, annotation_value, source)
);
CREATE INDEX idx_session_annotation_session ON t_session_annotation(session_id);
CREATE INDEX idx_session_annotation_type_value ON t_session_annotation(annotation_type, annotation_value);
CREATE INDEX idx_session_annotation_created ON t_session_annotation(created_at);

CREATE TABLE t_session_pattern (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature           VARCHAR(256) NOT NULL UNIQUE,
    outcome             VARCHAR(32) NOT NULL,
    suspect_surface     VARCHAR(32) NOT NULL,
    top_failing_tool    VARCHAR(128),
    agent_id            BIGINT,
    member_count        INT NOT NULL DEFAULT 0,
    suggested_surface   VARCHAR(32),  -- V3 attribution 写，V1 = suspect_surface
    first_seen_at       TIMESTAMP NOT NULL,
    last_seen_at        TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_session_pattern_outcome ON t_session_pattern(outcome);
CREATE INDEX idx_session_pattern_agent ON t_session_pattern(agent_id);
CREATE INDEX idx_session_pattern_last_seen ON t_session_pattern(last_seen_at);

CREATE TABLE t_pattern_session_member (
    pattern_id  BIGINT NOT NULL REFERENCES t_session_pattern(id) ON DELETE CASCADE,
    session_id  VARCHAR(36) NOT NULL,
    added_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pattern_id, session_id)
);
CREATE INDEX idx_pattern_member_session ON t_pattern_session_member(session_id);
```

**注意**：
- `session_id` 是 VARCHAR(36) 跟 t_session.id 类型对齐
- 没加 FK 到 t_session 避免迁移期复杂性；通过应用层校验
- `signature` UNIQUE 保证聚类重跑幂等
- 所有时间戳走 `TIMESTAMP` 类型（与现有迁移风格一致）

### 2.2 Seed migration `V73__seed_session_annotator_agent.sql`

参考 V69（memory-curator）模式 seed 一个 system agent，**采用新约定**：`owner_id = 1` + `is_public = TRUE`（V69 memory-curator 留 `owner_id = NULL` 不动）。

```sql
INSERT INTO t_agent (
    name,
    description,
    model_id,
    system_prompt,
    skill_ids,
    tool_ids,
    config,
    owner_id,        -- ← 新约定：=1（admin user），非 V69 的 NULL
    is_public,       -- ← 新约定：TRUE（系统共用）
    status,
    execution_mode,
    created_at,
    updated_at
)
SELECT
    'session-annotator',
    'System agent: hourly LLM annotation of production sessions. '
        || 'Outputs (outcome, suspect_surface, confidence, reasoning) per session via '
        || 'SessionFetch + SessionAnnotate tools. Drives PROD-LABEL-CLUSTER (V1).',
    'claude-sonnet-4-6',  -- default; 用户在 dashboard 可改
    'SEE_FILE:session-annotator-system-prompt.md',  -- 由 SessionAnnotatorBootstrap 启动加载
    '[]',
    '["SessionFetch","SessionAnnotate"]',
    '{"temperature": 0.2, "maxTokens": 2048}',
    1,                 -- ← owner_id = 1（新约定）
    TRUE,              -- ← is_public = TRUE
    'active',
    'auto',
    NOW(),
    NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_agent WHERE name = 'session-annotator');
```

**约定决策（2026-05-14 ratify）**：
- **新 system agent 默认 `owner_id = 1` + `is_public = TRUE`**（"admin 拥有 / 系统共用"模式）
- **V69 memory-curator 留 `owner_id = NULL` 不动**（已发布的迁移不补 fix migration）
- V73 注释里 acknowledge "与 V69 不一致是有意的新约定"

**Model 配置说明**：`session-annotator.model_id` 是 `t_agent` 标准字段，**用户在 dashboard 的 Agents 页面像配置任何其他 agent 一样可改**。V73 seed 时填一个合理默认值（`claude-sonnet-4-6`，跟 memory-curator 同档；对成本敏感的用户可后续切到 Haiku 类）—— 但**这不是 V1 开发期决策，是 runtime 用户配置**。

prompt 文件 `classpath:session-annotator-system-prompt.md` 由 `SessionAnnotatorBootstrap` 启动时加载（复用 MemoryCuratorBootstrap 模板，避免长 prompt 转义到 SQL string 里）。

## 3. 后端组件清单

### 3.1 复用（不新写）

| 类 | 位置 | 复用程度 |
|---|---|---|
| `TraceScenarioImportService` 内部 reason 检测 | 现有，line 136-151 | 抽 package-private 方法 `detectReasons(trace, spans)` 给 V1 调用；**只重构 visibility，不改逻辑** |
| `LlmTraceRepository` / `LlmSpanRepository` | 现有 | 直接调 |
| `SubAgentRegistry` + `SubAgentTool` | 现有 | session-annotator 通过它派发 |
| `MemoryCuratorBootstrap` 模板 | 现有 | 复制成 `SessionAnnotatorBootstrap` |
| `LlmMemorySynthesisScheduler` 模板 | 现有 | 复制成 3 个 V1 cron（lock 模式照抄） |

### 3.2 新建

| 组件 | 类型 | 备注 |
|---|---|---|
| `SessionAnnotationEntity` / `SessionAnnotationRepository` | JPA | 标准 |
| `SessionPatternEntity` / `SessionPatternRepository` | JPA | 标准 |
| `PatternSessionMemberEntity` / `PatternSessionMemberRepository` | JPA | 关联表 |
| `SessionAnnotationService` | Service | upsert + 幂等 + source 区分 |
| `SignalAnnotationJob` | Service + @Scheduled | Stage A，复用 detectReasons |
| `LlmAnnotationJob` | Service + @Scheduled | Stage B，派 session-annotator |
| `PatternClusteringJob` | Service + @Scheduled | Stage C，简单 bucket |
| `SessionAnnotatorBootstrap` | @Component | 启动时同步 system agent |
| `classpath:session-annotator-system-prompt.md` | 资源文件 | agent 系统 prompt |
| `SessionFetchTool` | Tool | agent 工具：拿一批 session 的基本信息 + 末尾几条 message |
| `SessionAnnotateTool` | Tool | agent 工具：写一条 outcome+suspect_surface 到 t_session_annotation |
| `InsightsController` | REST | `GET /api/insights/patterns` + `GET /api/insights/patterns/{id}/members` |
| `SurfaceType` enum | Core | skill / prompt / behavior_rule / tool / hook / mcp / other / unclear |
| `OptimizableSurface<V>` 空接口 | Core | V4 之前不实现，留扩展位 |

**注意**：`OptimizableSurface<V>` 在 V1 只是空接口骨架（loadActive / createCandidate / promote / rollback 方法声明），不要任何实现类。这是 ratify 决策"架构留扩展位"的最小落地。

## 4. session-annotator Agent 设计

### 4.1 System prompt 骨架（写到 `classpath:session-annotator-system-prompt.md`）

```
You are session-annotator, a system agent that classifies SkillForge production
sessions by outcome and suspected optimization surface.

For each session you receive, output ONE structured judgment via the
SessionAnnotationWrite tool with these fields:
- outcome: success | partial_success | failure | cancelled
- suspect_surface: skill | prompt | behavior_rule | other | unclear
- confidence: 0..1
- reasoning: 1-2 sentence rationale

Use SessionFetch first to get the session transcript tail + key trace events.
Do NOT label more than what was asked. Do NOT propose fixes — that's a
separate agent's job.

Suspect surface heuristics:
- skill: session failed because a skill returned wrong/incomplete output
- prompt: session failed because agent misunderstood user intent or
  produced rambling/off-task responses
- behavior_rule: session failed because agent violated established
  behavior rules (no rule citation, wrong escalation pattern, etc.)
- other: failure cause clearly outside the 3 above (LLM timeout, network)
- unclear: not enough signal to decide
```

### 4.2 Dispatch shape

`LlmAnnotationJob` 派发参数（参考 memory-curator 派发协议）：

```
SubAgentDispatch.dispatch(
    "session-annotator",
    payload={
        "session_ids": [...],  // 批量，但 agent 一条一条 SessionFetch + SessionAnnotationWrite
        "window": "1h"
    },
    timeoutMs=600_000
)
```

session-annotator 内部 loop：拿 session_ids → 逐条调 SessionFetch + 判断 + SessionAnnotationWrite。

### 4.3 Tool 接口（粗略，落地 IT 时细化）

- `SessionFetchTool`：input `{sessionId}` → output `{agentName, runtimeStatus, runtimeError, completedAt, messageTail (last 8 messages), traceSummary (totalCost / totalDuration / hasError)}`
- `SessionAnnotateTool`：input `{sessionId, outcome, suspect_surface, confidence, reasoning}` → 写 t_session_annotation (source=llm)，幂等（同 session 已有 outcome 标签则更新）

## 5. 聚类策略

### 5.1 Cluster key

```
signature = outcome + "|" + suspect_surface + "|" + top_failing_tool + "|" + agent_id
```

其中 `top_failing_tool` 通过查 session 关联 trace 的 tool span 取 error 出现频次最高的 tool name。无 tool failure 则填 `null`，signature 段写空字符串。

### 5.2 准入门槛

- bucket 至少 3 个 session member 才入 `t_session_pattern`
- 只聚 outcome 非 `success` 的 session（成功 session 不进 pattern，省存储）
- < 0.5 confidence 的 llm label 不参与聚类

### 5.3 重跑幂等

- `clustering-cron` 每次跑：(1) 扫过去 7 天有新 label 的 session（2）按 signature 重算 bucket（3）upsert `t_session_pattern`（4）增量插入新 member（5）更新 `member_count` / `last_seen_at`
- 不删除老 pattern，即使 member 数量降回 0 也保留（V3 attribution 可能引用）

## 6. Dashboard

### 6.1 REST endpoints（新建 `InsightsController`）

- `GET /api/insights/patterns?outcome=&surface=&agent=&limit=50` → 返回 pattern list
- `GET /api/insights/patterns/{id}/members?limit=100` → 返回 member sessions

### 6.2 前端组件

- 新 page `skillforge-dashboard/src/pages/Insights.tsx`
- 新组件 `PatternList.tsx` + `PatternDetailDrawer.tsx`
- 路由加 `/insights/patterns`
- 跳 trace 用现有 `/traces?sessionId=...` 深链接（OBS-2 已实现）

## 7. 实施计划

- [ ] **Phase 1.0：证伪 + 红测试**
  - 跑现有 memory-curator dispatch 一次，确认接通
  - 调 `TraceScenarioImportService` reason 检测 stub 单测
  - 写红测试：fake session + tool error trace → 跑 signal stage 期望 `tool_failure` 标签出现
- [ ] **Phase 1.1：DB + Entity + Repository + IT**
  - V72/V73 migration
  - 3 个 entity + repository + JPA IT
- [ ] **Phase 1.2：Signal Stage**
  - 重构 `TraceScenarioImportService.detectReasons` visibility（package-private）
  - `SignalAnnotationJob` + cron
  - 红测试转绿
- [ ] **Phase 1.3：session-annotator Agent**
  - `SessionAnnotatorBootstrap` + classpath prompt
  - 2 个新 Tool (SessionFetchTool / SessionAnnotateTool)
  - `LlmAnnotationJob` + cron
  - 派一次 dispatch 端到端验证（manual test）
- [ ] **Phase 1.4：Clustering + InsightsController**
  - `PatternClusteringJob` + cron + 幂等测试
  - `InsightsController` + 2 endpoint
- [ ] **Phase 1.5：Dashboard 页**
  - `Insights.tsx` + `PatternList` + `PatternDetailDrawer`
  - 路由 + nav
- [ ] **Phase Final：Verify & Commit**
  - `mvn test` 全绿
  - `npm run build` EXIT 0
  - 跑一遍真实生产数据 hourly cron，spot-check
  - `git diff` 确认核心文件零改动
  - Reviewer: `java-reviewer` + `typescript-reviewer` + `database-reviewer`（新表 + JPA）

## 8. 风险 & 边界

| 风险 | 缓解 |
|---|---|
| `TraceScenarioImportService` 现有方法签名调整影响 SmartImport | 改 visibility 而非签名，加单元测试锁住输出格式 |
| LLM 标注错误率拖累聚类 | confidence < 0.5 不入聚类；source 字段区分便于后期人工修正 |
| 三个 cron 互相依赖时序错位 | hourly 错开 + advisory lock + 各 stage 幂等 |
| 三个新表磁盘膨胀 | t_session_annotation 加 created_at 索引；7 天滚动清理（V1 不做，V3 加） |
| 派 session-annotator agent 成本失控 | 单批 ≤ 10 session；agent 内 max_loops 限制；hourly 限 1 次 |
| Phase 1.3 派 agent 跨进程调试困难 | 走 memory-curator 现有 SubAgentDispatch + 派发日志，不开新通路 |

## 9. 与现有规则的关系

- 遵守 [`docs-reading.md`](../../../../.claude/rules/docs-reading.md)：本包 prd + tech-design 是实现入口
- 遵守 [`think-before-coding.md`](../../../../.claude/rules/think-before-coding.md)：Phase 1.0 证伪先行，scope discipline 不动核心文件
- 遵守 [`verification-before-completion.md`](../../../../.claude/rules/verification-before-completion.md)：Completion Gate 三件套 + spot-check 20 条标签
- 遵守 [`pipeline.md`](../../../../.claude/rules/pipeline.md) Mid 档：1 dev → 2 reviewer 1 轮对抗 → Judge → Phase Final
- 遵守 [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) + [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md)：本包不动 t_session / t_session_message 因此不触这两条 Iron Law，但 reviewer 需 grep diff 确认

## 10. 变更记录

- 2026-05-14：claude 初稿（design-draft）
