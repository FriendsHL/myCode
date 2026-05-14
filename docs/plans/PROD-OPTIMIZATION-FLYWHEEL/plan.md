# PROD-OPTIMIZATION-FLYWHEEL 整体方案

> 状态：**V1 已 ratify，已建需求包 PROD-LABEL-CLUSTER**
> 创建：2026-05-14
> 更新：2026-05-14（ratify 决策固化 + 复用清单）
> 草拟方：claude / youren

---

## 一、问题陈述

把"数据飞轮 / 优化闭环"在 SkillForge 上做完整。当前 skill 这一条线走通了大部分，但有"最后一公里"没合；其他可优化项（agent system prompt / behavior rule / lifecycle hook / tool）能力零散。需要：

1. **生产 session 自动标注 + 跨 session 失败聚类**（飞轮的起点信号）
2. **归因分析 agent**：基于标注聚类输出"该优化哪一项 / 改成什么"的结论
3. **每个可优化项有独立的 candidate 生成 + A/B 评测 + promote 通路**
4. **灰度上线 + 灰度期生产指标回流**（你说的"重新跑一次证明效果比之前的好"）
5. **整条链可追溯**（哪条生产现象 → 哪个改动 → 改进后哪些 prod 指标变化）

---

## 二、现状盘点（基于 2026-05-14 代码调研）

### 2.1 数据层（生产 session 信号源）

**已有信号字段**：
- `t_session.runtimeStatus`（idle / running / waiting_user / error）+ `runtimeError`
- `t_session.origin`（production / eval）+ `sourceScenarioId`（已分离两路流量）
- `t_session.activeRootTraceId`（OBS-4 跨 agent 串）
- `t_llm_trace.status`（running / ok / error / cancelled）+ `error` + `totalCostUsd` + `totalDurationMs` + `toolCallCount`
- `t_llm_span.kind`（llm / tool / event）+ tool span 的 `error` / `errorType` / `finishReason`

**Smart Import 已落地 6 类 reason**（直接查 trace/span）：
- agent_error / tool_failure / span_error / high_token / multi_turn / has_tool_calls

**关键缺口**：
- **没有 `t_session_annotation` 表**：生产 session 没有任何"标注"列，attribution 信息只在 eval 框架（EvalTaskEntity.FailureAttribution）
- **没有跨 session 聚类**：EvalAnalysisSession 只是 per-task 单 case 分析 chat，不存 pattern
- **没有 production 维度的 outcome 字段**：success / partial / fail / cancelled 现在要从 runtimeStatus + trace.status 临时拼

### 2.2 评测 + 归因（eval 侧）

**EVAL-V2 M0-M6 实际能力**：scenario versioning / multi-turn judge / 4 维分数（quality/efficiency/latency/cost）+ dimensionStatus / per-item attribution / annotation queue / smart import / dataset versioning。

**归因当前限制**：仅 per-scenario / per-task / per-item 三档单 case，**无 cross-session pattern 聚类**。

### 2.3 Skill 进化闭环

| 环节 | 状态 |
|---|---|
| Skill 数据模型（SkillEntity / SkillDraftEntity / SkillAbRunEntity） | ✅ 完整 |
| Draft 抽取（SkillDraftService.extractFromRecentSessions）| ✅ 单 LLM call / 10 session truncate |
| Draft 相似度 + merge gate | ✅ |
| A/B 评测（SkillAbEvalService + SandboxSkillRegistryFactory）| ✅ single-turn 走通；**multi-turn 在 SKILL-AB-MULTITURN-FIX 修中** |
| Promote 自动阈值（delta ≥ 15pp & candidate ≥ 40pp）| ✅ |
| Manual Override（manualPromote）| ✅ |
| **灰度（canary）** | ❌ 没有 rolloutPercentage / canary state |
| **Rollback / Kill Switch** | ❌ 只能手动 disable |
| **生产指标回流** | ❌ promote 后是盲区 |

### 2.4 其他可优化项（surface）

| Surface | Versioning | Sandbox 注入 | 评分 |
|---|---|---|---|
| **A. Agent System Prompt** | ✅ PromptVersionEntity + PromptAbRunEntity + activePromptVersionId | ✅ PromptImproverService / AbEvalPipeline | **8.5/10 成熟** |
| **B. Tool 注册/配置** | ❌ 代码硬注册（SkillForgeConfig） | ❌ | **3/10** |
| **C. Lifecycle Hook** | ❌ AgentEntity.lifecycleHooks JSON 直接覆盖 | 部分 | **4.5/10** |
| **D. 行为 Rule** | ❌ behavior-rules.json + AgentEntity.behaviorRules JSON | ❌ | **3.5/10** |

A 已经具备完整闭环模板（PromptVersionEntity / PromptAbRunEntity / PromptPromotionService），是其他 surface 的参考形态。

---

## 三、产品方案（飞轮闭环）

```
                ┌──────────────────────────────┐
                │  生产 session 流入            │
                │  (t_session origin=production)│
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ① 标注   │  SessionAnnotationService          │
                │  - signal-based (trace/span)  │
                │  - llm-based (outcome)         │
                │  - human override (annotation) │
                │  → t_session_annotation             │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ② 聚类   │  PatternClusteringService     │
                │  按 (failure_class × tool ×    │
                │   agent × skill) 桶聚         │
                │  → t_session_pattern          │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ③ 归因   │  AttributionAgent (sub-agent)  │
                │  - 读 pattern + 抽样 session   │
                │  - 决策"该改哪个 surface"      │
                │  → t_optimization_event(stage=  │
                │     attribution_done)          │
                └──────────────┬───────────────┘
                               │
       ④ 生成 candidate         │
       (按 surface 分支)         │
                ┌──────────────▼───────────────┐
                │  SkillDraftService            │
                │  PromptImproverService        │
                │  BehaviorRuleImproverService  │
                │  LifecycleHookImproverService │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ⑤ A/B   │  AbEvalRunner<Surface>        │
                │  (统一抽象，sandbox 注入)      │
                │  → AbRunEntity per surface    │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ⑥ Gate  │  PromoteGate                  │
                │  阈值 OR manual approve       │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ⑦ 灰度   │  CanaryRolloutService         │
                │  rolloutPercentage 0→100      │
                │  CanaryAllocator              │
                │  (session_hash % pct)         │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ⑧ 回流   │  ProdMetricsCollector         │
                │  hourly aggregate from         │
                │  t_session_annotation              │
                │  canary vs control 对比        │
                │  → t_canary_metric_snapshot   │
                └──────────────┬───────────────┘
                               │
                ┌──────────────▼───────────────┐
       ⑨ 决策   │  - 通过 → 100% 上线           │
                │  - 失败 → 自动 rollback        │
                │  - 整条链写 t_optimization_   │
                │     event(stage=verified)     │
                └──────────────┬───────────────┘
                               │
                               └──→ 回到 ① 进入新一轮
```

### 关键产品决策

1. **第一版 surface 范围**：仅含 **skill + agent prompt + behavior rule**（A/D + skill），不含 tool（重构成本高，ROI 低）、lifecycle hook（视用户使用频率决定 V4 是否纳入）
2. **标注双通道**：signal-based（trace/span 派生）+ llm-based（outcome / business_intent）；两者写同一张 `t_session_annotation`，用 `source` 字段区分；人工修标作 P2 加入
3. **归因结论必须落 `t_optimization_event`**：每个 event 记录"哪个 pattern → 决定改哪个 surface → 哪个 ab_run → 哪次 promote → 哪次 canary → 哪批 prod metric 验证"全链路
4. **灰度统一抽象**：所有 surface 共用 `CanaryAllocator`，按 `session_hash % rolloutPercentage` 决定一条生产 session 用 active 还是 candidate 版本
5. **不破坏现有"全量直接 enable"路径**：rolloutPercentage 默认 100 = 与现行行为完全等价；只有显式走 canary 才会分流
6. **回流指标 hourly batch**，不上实时（实时会过度耦合 ChatService 核心路径）

---

## 四、技术方案骨架（细节延后）

### 4.1 新增持久化

| 表 | 作用 | 关键字段（粗略）|
|---|---|---|
| `t_session_annotation` | 生产 session 多标签 | session_id, annotation_type, annotation_value, source(signal/llm/human), confidence, created_at |
| `t_session_pattern` | 跨 session 聚类输出 | id, signature, pattern_type, member_count, agent_id, suggested_surface, created_at |
| `t_pattern_session_member` | pattern ↔ session 多对多 | pattern_id, session_id |
| `t_optimization_event` | 飞轮因果链 | id, pattern_id, attribution_session_id, surface_type(skill/prompt/rule), candidate_version_id, ab_run_id, canary_id, stage, prod_verified, created_at |
| `t_canary_rollout` | 灰度状态机 | id, surface_type, agent_id, active_version_id, candidate_version_id, rollout_percentage, started_at, ended_at, decision(promoted/rolled_back) |
| `t_canary_metric_snapshot` | hourly 灰度指标 | canary_id, hour_bucket, control_success_count, control_fail_count, candidate_success_count, candidate_fail_count, p_value |
| `t_behavior_rule_version` | 行为规则版本（V4 引入）| 类比 PromptVersionEntity |
| `t_behavior_rule_ab_run` | 行为规则 A/B（V4 引入）| 类比 PromptAbRunEntity |

**核心原则**：
- **不动 t_session / t_session_message 任何已有列**（核心文件 + identity-column-on-rewrite 风险）；新表通过 session_id 外键关联
- 涉及 SessionService.rewriteMessages 一律不碰

### 4.2 新增服务层

```
SessionAnnotationService           # 写 t_session_annotation（signal 批量 + llm 单条）
PatternClusteringService      # 跨 session 聚类，定时 cron 跑
AttributionAgent              # 一个 system sub-agent（参考 memory-curator 模式）
  - 复用 SubAgentDispatchService
  - 给它新 tool：SessionAnnotationRead / PatternRead / SessionMessageRead
                ProposeOptimization / WriteOptimizationEvent
CandidateGenerator (per surface)
  - skill: SkillDraftService（已存在 → 加 trigger-from-pattern 入口）
  - prompt: PromptImproverService（已存在）
  - behavior_rule: BehaviorRuleImproverService（新建，复用 PromptImprover 模式）
AbEvalRunner<V>               # 抽取 SkillAbEvalService + AbEvalPipeline 共同骨架
PromoteGate                   # 已有阈值 + manual 入口，集中化
CanaryRolloutService          # 写 t_canary_rollout / 启停 canary
CanaryAllocator               # 运行时分流：session_hash % rolloutPercentage
ProdMetricsCollector          # hourly cron 聚合 t_session_annotation → t_canary_metric_snapshot
OptimizationEventService      # 写 / 查 / 串 t_optimization_event
```

### 4.3 统一抽象（V1 起留扩展位 / V4 才填实）

**V1 落地（空骨架）**：
```java
public enum SurfaceType { SKILL, PROMPT, BEHAVIOR_RULE, TOOL, HOOK, MCP, OTHER, UNCLEAR }

public interface OptimizableSurface<V> {
    SurfaceType type();
    V loadActive(String agentId);
    V loadVersion(String versionId);
    V createCandidate(String agentId, String baseVersionId, String source);
    void persistCandidate(V candidate);
    String startAbRun(String agentId, String candidateVersionId, String baselineRunId);
    void promote(String versionId);
    void rollback(String versionId);
    V loadForSandbox(String agentId, String sandboxVersionId);
}
```

**为什么 V1 就建好接口** ⚠️：用户 ratify 决策 "后续加 tool / hook / mcp 不需要改大框架"。接口骨架在 V1 落地（无实现类），V2/V3 各自加 Skill / Prompt 实现类，V4 加 BehaviorRule 实现类时触发 `AbstractAbEvalRunner` Template Method 提取 —— 此时复用现有 `SkillAbEvalService` + `AbEvalPipeline` 骨架，**不重写**。

**模式应用**：
- Strategy Pattern → `OptimizableSurface<V>` 接口
- Registry Pattern → `SurfaceRegistry`（SurfaceType → Handler 映射，Spring `@Component` 自动注入）
- Template Method → `AbstractAbEvalRunner`（V4 才提取）
- State Pattern → `t_canary_rollout` 状态机
- Observer Pattern → Spring `ApplicationEventPublisher` 写 `t_optimization_event`

### 4.4 运行时分流路径（V2 引入）

```
ChatService 接到 user message
  ↓
SessionService 写 session
  ↓
AgentLoopEngine 启动前：
  for each surface in [skill, prompt, behavior_rule]:
      activeVersionId = CanaryAllocator.allocate(sessionId, surface, agentId)
      → 拿到 active or candidate
  ↓
Agent loop 跑（用 allocator 拿到的 version 组合）
  ↓
完成后 SessionAnnotationService 异步标注 → 信号回流
```

**关键不变量**：分流决策 per-session 锁定（同一个 session 不能中途切版本），靠 `t_session_annotation.annotation_type=canary_assignment` 持久化。

### 4.5 风险与边界（提前预警）

| 风险 | 缓解 |
|---|---|
| 改 AgentLoopEngine 触红灯（核心文件清单）| V2 起触碰必走 Full pipeline + compact-reviewer + identity-column 检查 |
| t_session_annotation 写入打 ChatService 路径性能 | 走异步队列 + hourly batch，不在主路径同步写 |
| LLM 标注错误率 | source 字段区分 signal/llm/human；人工标注队列 V3 加入；llm 标签置信度 <0.7 不入聚类 |
| Canary 把生产用户卷进失败版本 | 默认 percentage=5% 起步 + auto-rollback signal（candidate fail rate / baseline >1.5 倍 → 立即停）|
| OptimizationEvent 因果链造假 | 每个 stage 转换必须 atomically 写一次 event；不允许 service 跳过 |
| Multi-surface 同时灰度 confounding | V2 限制：同一 agent 同时只能有 1 个 surface 在 canary（互斥锁）|

---

## 五、版本拆分

### V0（前置，已在队列）— SKILL-AB-MULTITURN-FIX

**状态**：design-draft（2026-05-13 创建）
**档**：Mid
**目标**：修 SkillAbEvalService 多轮 fallback，让 skill A/B 真正跑 conversationTurns。
**与本飞轮关系**：V2 灰度依赖 skill A/B 多轮跑通；不阻塞 V1。

---

### V1 — 生产 Session 标注 + 失败聚类 MVP

**档**：Mid（新表 + 新 Service + 新页面；不动核心路径）
**ID**：`PROD-LABEL-CLUSTER`
**目标**：飞轮的第①②步落地。让"哪些生产 session 失败 / 失败聚成几类 / 每类多大体量"在 dashboard 上可见。

**功能范围**：
- 新表 `t_session_annotation` + `t_session_pattern` + `t_pattern_session_member`
- `SessionAnnotationService`：
  - signal-based 复用 Smart Import 6 reason 查询 → 写标签（cron hourly）
  - llm-based outcome 标注（success / partial_success / failure / cancelled）— 单条 LLM call per session
- `PatternClusteringService`：
  - 简单 bucket 聚类（按 `failure_class × tool × agent` 维度分组），**不上 ML 聚类**
  - 每个 cluster 至少 3 个 member 才入库
- Dashboard 新页面 `/insights/patterns`：
  - 失败 pattern 列表（size / first_seen / suggested_surface）
  - 点开看 member sessions 列表 + 抽样 transcript

**不做**：
- 不接 agent / 不接 attribution agent（V3 才做）
- 不上 ML 聚类
- 不动 SessionEntity / ChatService / SessionService 核心
- 不接 candidate 生成（仅产出"该看哪些"的信号）

**验收**：
- 跑一周生产数据，dashboard 至少能展示 5+ pattern
- 每个 pattern 能 drill-down 看到具体 session
- 人工 spot-check 标签准确率 > 70%（llm outcome label）
- 现有 Smart Import 流程不退化

**预估**：~600 行后端 + ~400 行前端，1-2 周

---

### V2 — Skill 闭环最后一公里：灰度（架构保留）+ 生产指标回流

**档**：Full（触碰 AgentLoopEngine 核心 + 加 schema）
**ID**：`SKILL-CANARY-ROLLOUT`
**前置**：V0 SKILL-AB-MULTITURN-FIX + V1 PROD-LABEL-CLUSTER 都完成 + **V1 跑 1 周 dogfood 看 outcome 标签信号准不准**（V1 §8 决策 8）
**目标**：飞轮第⑦⑧⑨步对 skill 这一条 surface 落地。**默认一刀切**（rolloutPercentage=100 等于现行），灰度作 opt-in 模式保留为多用户阶段用。

**功能范围**：
- `t_canary_rollout`（状态机：disabled / canary / production / rolled_back）+ `t_canary_metric_snapshot` 表
- `SkillEntity` 加 2 列：`rolloutStage`、`rolloutPercentage`（默认 100，等价现行行为）
- `CanaryRolloutService`：启停 + 调档 + auto-rollback 信号
- `CanaryAllocator`：sessionId hash % percentage → 决定本次 session 用 active 还是 candidate skill
- 改 AgentLoopEngine / SkillRegistry skill 加载入口前挂一层 allocator 查询（**核心文件红灯**）
- `ProdMetricsCollector` hourly cron：聚合 t_session_annotation 的 outcome 标签，按 sessionId 反查 canary group → 写 t_canary_metric_snapshot
- Dashboard skill 详情页加 canary panel：rollout gauge / 24h 指标 / publish 按钮 / rollback 按钮
- 配置项 `auto_promote_after_ab`（默认 false = A/B 通过等人按 publish；true = 自动）
- Auto-rollback：仅在 opt-in 灰度模式下生效（一刀切不触发）

**复用清单（V1 经验 + 项目现有）**：
- ✅ `SkillAbEvalService` 全套（createAndTrigger / runAbTestAsync / promoteCandidate / manualPromote / delta 阈值）不动
- ✅ `SandboxSkillRegistryFactory.buildSandboxRegistryWithSkills` skill sandbox 注入零改动
- ✅ `EvalScoreFormula` M4_V2 4 维分数 + dimensionStatus → 直接是 canary control vs candidate 指标对比轴
- ✅ V1 `t_session_annotation` outcome 标签 → ProdMetricsCollector 唯一数据来源
- ✅ V1 `t_session_pattern` → 如果 canary group 整体掉进某个 fail pattern，强信号触发 auto-rollback
- ✅ 现有 `SkillEntity`（parent_skill_id / artifact_status / version）→ 只加 2 列
- ✅ `SkillEvolutionPanel.tsx` / `SkillAbPanel.tsx` → canary panel 嵌入扩展，不重写
- ✅ V1 落地的 `OptimizableSurface<V>` 空接口骨架 → V2 给 skill 填第一个实现类（不抽 Template Method，等 V4）

**新建清单**：
- 2 张表 + Entity / Repository（标准三件套）
- `CanaryAllocator`（核心运行时分流）
- `CanaryRolloutService`（状态机操作 + auto-rollback 触发）
- `ProdMetricsCollector` 走 P12 ScheduledTask（**复用 V1 ScheduledTask 模式，不写 @Scheduled**）
- Skill 详情页 canary panel 组件 + Publish/Rollback 按钮
- 2 个 Flyway migration（schema + ScheduledTask seed）

**待 ratify 决策（开工前拍）**：
1. **canary 默认起步比例（多用户阶段）**：5% / 10% / 20% —— 看 V1 dogfood 跑出多少 prod session/天再定（推荐 10% 但要数据支撑）
2. **session canary 组绑定持久化**：每次 dispatch 现算 hash % pct（无状态）vs 写 `t_session_annotation` (annotationType="canary_group")（有状态可追溯）—— 推荐**有状态写到 t_session_annotation**（V1 表已 ready 直接复用，不另开表）
3. **Auto-rollback 阈值**：candidate fail_rate / control fail_rate > 1.5 且样本 > 50 —— 这两个值跟 V1 实际 outcome 标签准确率挂钩，等 V1 dogfood spot-check 20 条准确率后再定
4. **同 agent canary 互斥**：advisory lock vs DB unique constraint —— 推荐 unique constraint on (agent_id, surface_type, rolloutStage='canary')，简单 + 持久 + 重启不丢
5. **CanaryAllocator 注入点**：AgentLoopEngine spawn skill 之前 vs SkillRegistry 查 skill 时 —— **推荐前者**（AgentLoopEngine 已是核心文件，多一处分流逻辑加在那里集中），但要 reviewer 显式审 persistence-shape-invariant
6. **ProdMetricsCollector 频率**：hourly / 6-hourly / daily —— hourly 跟 V1 同步省脑（推荐）

**已知 footgun（V1 踩过 + 项目特点）**：
- ⚠️ **Flyway 版本号占用**：开工前 grep `ls db/migration/V*.sql` 找下一个可用号（V1 经验：V72/V73 被 multimodal 占用）
- ⚠️ **TIMESTAMPTZ not TIMESTAMP**（V1 经验，跟 V70+ 一致）
- ⚠️ **per-row saveAndFlush + catch DIVE 在 PG aborted-tx 静默丢数据**（V1 Phase 2 修过）—— 任何写多行操作用 `INSERT ... ON CONFLICT DO NOTHING RETURNING id`
- ⚠️ **AgentLoopEngine 触碰双红灯**：identity-column-on-rewrite + persistence-shape-invariant 两条 Iron Law 必须 reviewer 显式审，commit message acknowledge
- ⚠️ **CanaryAllocator 决策必须 per-session 锁死**（同 session 不能中途切版本，否则 prompt cache 失效 + agent 状态错乱）—— ratify 决策 2 的方案选择会决定怎么持久化
- ⚠️ **ProdMetricsCollector 跑空数据时不能崩**：V1 开始头 24h pattern 可能 0 条，canary group 还没 sample → 指标计算注意 divide-by-zero
- ⚠️ **promoteCandidate 现行行为不能破坏**：rolloutPercentage 默认 100 = 现行一刀切。reviewer 必须 regression 测现有 skill evolution 自动 promote 路径

**不锁的具体（等开工时按数据决定）**：
- canary panel 24h 指标图表的具体维度组合（quality / efficiency / latency / cost / outcome rate？）
- ProdMetricsCollector 写 t_canary_metric_snapshot 的字段精度（DECIMAL 几位？）
- Auto-rollback 触发后的 alert 渠道（dashboard toast / email / 不发？）
- 默认配置 `auto_promote_after_ab=false` vs `true`（看用户偏好）

**前置依赖关键检查**：
- V1 dogfood 后 outcome 标签人工 spot-check 准确率 > 70%（PRD 验收标准）—— 不达标 ratify #3 阈值就没基础

**验收（迁移自原版 + 加细节）**：
- 把一个 skill 上 canary 10% → 24h → 指标看板能看到 control vs candidate 4 维分数对比
- 人工触发 publish → percentage 100% + active 切换 + 现有 promote 路径同步
- 人工注入坏 candidate → auto-rollback 触发 + dashboard 告警 + 状态机置 rolled_back
- 现有 SkillAbEvalService promoteCandidate 全量路径不退化（rolloutPercentage 默认 100）+ 现有 SkillEvolutionPanel 正常显示
- AgentLoopEngine 加 allocator 路径不破 persistence-shape / identity-column 两条 Iron Law

**预估**：~1500 行后端 + ~600 行前端 + 2 Flyway migration，2-3 周

---

### V3 — Attribution Agent + Optimization Event 因果链

**档**：Full（新 system agent + SubAgent 集成 + 新表）
**ID**：`ATTRIBUTION-AGENT`
**前置**：V1 完成（pattern 可读）+ V2 完成（canary 可写 metrics） + **V1 跑数据后实际 pattern 列表能挑出至少 1-2 个值得 attribute 的真实失败 pattern**（不然 V3 没 input）
**目标**：飞轮第③⑤⑥步打通自动化通路 —— 从 pattern → "改哪个 surface 改成什么" → 自动起 candidate → 自动发起 A/B + canary，**半自动**（人工 approve proposal 才起 candidate，per V1 ratify 决策 5）

**功能范围**：
- `t_optimization_event` 因果链表（stages: pattern → attribution_proposed → proposal_approved → candidate_created → ab_run → canary_started → canary_verified → promoted）
- 新 system agent `attribution-curator`（参考 memory-curator + V1 session-annotator 双模板，**同款 ScheduledTask + 1 agent + 多 tool orchestrate**）
- `AttributionDispatcher`（cron / 手动 trigger，对未 attribute 的 pattern 派发一次 attribution-curator）
- Dashboard：
  - optimization event 时间轴页（pattern → attribution → candidate → ab → canary → verified 全链路）
  - Pending Approval 队列（attribution-curator 产出的 proposal 等人按 approve 才起 candidate）

**复用清单（V1+V2 经验）**：
- ✅ V1 `t_session_pattern` + `t_pattern_session_member` → attribution agent 直接读
- ✅ V1 `t_session_annotation` → attribution agent 取 session 怀疑根因细节
- ✅ V1 `SessionAnnotatorBootstrap` + classpath prompt + V69 dogfood 模式 → attribution-curator agent 完全照搬模板
- ✅ V1 `GetTraceTool`（V76 接入）→ attribution agent 复用，看 trace 细节
- ✅ V1 P12 ScheduledTask + concurrency_policy='skip-if-running' → 同款触发模式
- ✅ V1 `EvalAnalysisSessionEntity` 模型 → 扩 analysisType enum 加 `PATTERN_LEVEL`，不新建表
- ✅ V1 `AnalyzeEvalTaskTool` 写 attributionSummary 模式 → attribution-curator 的 ProposeOptimization tool 直接 copy 形态
- ✅ V1 `SubAgentRegistry` / `SubAgentTool` → 零改动派发
- ✅ V2 `CanaryAllocator` / `CanaryRolloutService` → attribution 自动通过后自动起 canary
- ✅ 现有 `SkillDraftService.extractFromRecentSessions` → attribution proposal 触发它，**不开第二条 skill 抽取路径**
- ✅ 现有 `PromptImproverService.startImprovement` → attribution proposal 触发它，**不开第二条 prompt 改进路径**
- ✅ 现有 `SkillEvolutionPanel` timeline UI 模式 → optimization event timeline 同款渲染

**新建清单**：
- `t_optimization_event` 表 + state machine service（Spring `ApplicationEventPublisher` 监听 stage 转换 atomic 写 event）
- `attribution-curator` system agent + 5-6 个 tool：
  - `PatternRead`（读 t_session_pattern + members）
  - `SessionAnnotationRead`（读 t_session_annotation by sessionId）
  - `ProposeOptimization`（产出 surface + 改法 + 期望效果的结构化 JSON）
  - `WriteOptimizationEvent`（写状态机转换）
  - 复用 `GetTrace`（V76 已注册，加到 attribution-curator 的 tool_ids）
- `AttributionDispatcher`（cron / 手动触发）
- `OptimizationEventService` + REST `/api/insights/optimization-events`
- Dashboard 时间轴页 + Pending Approval 列表

**待 ratify 决策（开工前拍）**：
1. **半自动 vs 全自动**：V1 ratify 决策 5 已锁定 **半自动**（proposal 等人 approve）—— V3 复用，不改
2. **同一 pattern 触发 attribution 冷却**：attribute 失败后多久允许重试（防重复 spend token）—— 推荐 24h cooldown 字段 + manual override
3. **跨 surface proposal**：同 pattern 是否允许同时建议改 skill + prompt？—— V3 推荐 **单 surface only**（agent 必须选 1 个 best surface），跨 surface 留 V4 或 V5
4. **attribution-curator 输出格式**：JSON schema 该长啥样（surface + change_type + description + confidence + risk）—— 等 V1 pattern 真长出来再定结构（**不锁**）
5. **A/B 通过后自动 canary** vs **A/B 通过后等人按 publish**：V1 ratify #4 已锁 publish 按钮 → V3 同步（A/B 通过 → 写 event stage=ab_passed，**人工按 canary 按钮才进 V2 canary**）
6. **attribution agent 用哪个 model**：runtime t_agent.llm_model 用户配置（不锁）；seed 默认推荐 sonnet（attribute reasoning 需要 carefulness）

**已知 footgun（V1+V2 经验 + 项目特点）**：
- ⚠️ 复用 V1 dogfood 经验：V72-V76 占用后下个 migration 至少 V77+（开工前 grep 确认）
- ⚠️ `ApplicationEventPublisher` 同步事件 vs 异步：写 stage 转换 atomic 要求事务内同步发布 + 监听器同事务，否则可能 stage 写漂移
- ⚠️ system agent owner_id=1 + is_public=TRUE（V1 ratify 决策 7，V3 继承）
- ⚠️ tool_ids JSON 顺序 = agent prompt 调用顺序（V1 经验：[SignalDetect, GetTrace, Annotate, RecomputeClusters]）
- ⚠️ Bootstrap classpath:* + SEE_FILE: sentinel 重新加载机制（V1 V76 经验）
- ⚠️ attribution-curator 跑出 proposal 可能**很贵**（要看 pattern + 多个 member session + 写 reasoning）→ 单次 invocation 限 max_loops + 单批 pattern 数限 cap
- ⚠️ ProposeOptimization tool 不写 candidate（不直接调 SkillDraftService / PromptImproverService）→ 只写 t_optimization_event proposal stage 等人 approve；approve 触发 candidate 生成

**不锁的具体（等开工时按 V1 数据决定）**：
- attribution-curator system prompt 的 heuristics 细节（"什么样的 pattern 该指向 prompt vs skill"）—— 看 V1 跑出来 pattern 形态再写
- proposal JSON schema 字段（surface / change_type / description / expected_metric_delta）
- Pending Approval UI 设计（卡片式 / 列表式 / drawer）
- cooldown 字段 ratify #2 的具体小时数

**前置依赖关键检查**：
- V1 dogfood 后 dashboard `/insights/patterns` 至少能挑出 1-2 个真实 pattern 适合 attribute
- V2 canary 通路至少跑通 1 次 skill end-to-end（不然 V3 自动接 canary 拿不到数据）

**验收**：
- 给一个真实 pattern 跑 attribution-curator → 输出可读 proposal（人工评估 reasonable）
- proposal approve 后能自动触发 SkillDraftService 或 PromptImproverService，进 A/B
- A/B 通过 → 推 publish 按钮 → 自动进 V2 canary → canary 通过 → 写 event stage=promoted
- Dashboard 时间轴能完整还原一次飞轮跑
- 失败回滚也写 event（rolled_back stage）

**预估**：~1200 行后端（agent prompt + 5-6 个 tool + dispatcher + state machine）+ ~500 行前端，3 周

---

### V4 — Behavior Rule + Lifecycle Hook 纳入飞轮（OptimizableSurface 抽象提取）

**档**：Full（多 surface 统一抽象 + 重构 SkillAbEvalService + AbEvalPipeline 共用骨架 + 改两个核心配置路径）
**ID**：`MULTI-SURFACE-FLYWHEEL`
**前置**：V2 + V3 完成（skill 一路完整闭环 = 第一个 surface 验证）+ V3 attribution agent 真跑出过指向 behavior_rule 的 proposal（不然 V4 没切入信号）
**目标**：把 **behavior rule 必做** + **lifecycle hook 视使用率决定**接入同一飞轮。**第二、三个 surface 出现触发 V1 留的 `OptimizableSurface<V>` 空骨架填实 + 抽 `AbstractAbEvalRunner` Template Method 收口**。

**功能范围**：
- `t_behavior_rule_version` + `t_behavior_rule_ab_run` 表（模仿 `PromptVersionEntity` + `PromptAbRunEntity`）
- 可选：`t_lifecycle_hook_version` + `t_lifecycle_hook_ab_run`（视 V1-V3 实际 lifecycle hook 演进频率）
- `BehaviorRuleImproverService` + `BehaviorRuleAbEvalService`（复用 `PromptImproverService` + `SkillAbEvalService` 模板）
- 抽 `AbstractAbEvalRunner<V>` Template Method（**重构现有两个 service，不是新写第三个**）—— hook: extractBaseline / buildCandidateSandbox / runJudge / aggregateScore / promoteOnThreshold
- `OptimizableSurface<V>` 三个实现类（V1 骨架填实）：`SkillSurface` / `PromptSurface` / `BehaviorRuleSurface`
- `SurfaceRegistry`（Spring `@Component` 自动注入；按 SurfaceType enum 分发）
- `CanaryAllocator` 扩 surface 维度（V2 只支 skill，V4 改泛型）
- AttributionAgent 的 `ProposeOptimization` tool 接 behavior_rule 分支（V3 已留 stub）
- `BehaviorRuleRegistry` 改 active version 查表
- Dashboard：behavior rule 详情页加 canary panel（**复用 V2 组件**）

**复用清单（V1+V2+V3 经验 + 项目现有）**：
- ✅ 现有 `PromptVersionEntity` + `PromptAbRunEntity` + `PromptImproverService` + `PromptPromotionService` → behavior rule 表 + service 完全照抄模板
- ✅ 现有 `BehaviorRuleRegistry`（N2 已落地）+ `behavior-rules.json` 内置规则库 → baseline v0
- ✅ V2 `t_canary_rollout` + `t_canary_metric_snapshot` + `CanaryAllocator` + `CanaryRolloutService` + `ProdMetricsCollector` → 全部泛化为 surface 维度，不重写
- ✅ V3 `t_optimization_event` 状态机 → 不动，attribution agent 输出 surface=behavior_rule 时进 V4 流程
- ✅ V3 attribution-curator agent → 加 ProposeOptimization tool 一个 enum 分支即可
- ✅ V1 `OptimizableSurface<V>` 空接口骨架 → V4 才填三个实现类（**Strategy + Registry 模式**正式落地）
- ✅ 现有 `SkillAbEvalService` + `AbEvalPipeline`（prompt A/B）→ 重构提取共同骨架，不是丢弃重写
- ✅ 现有 `SandboxSkillRegistryFactory` → 模式扩展为 `SandboxSurfaceFactory<V>` 泛型

**新建清单**：
- 2 张表（behavior_rule_version + behavior_rule_ab_run；可选 +2 张 lifecycle_hook）
- `AbstractAbEvalRunner<V>` Template Method（**重构核心**，收口现有 2 个 service）
- `OptimizableSurface<V>` + 3 个实现 + `SurfaceRegistry`
- `BehaviorRuleImproverService` + `BehaviorRuleAbEvalService`
- 1 Flyway migration（schema）+ 可能 1 个 seed migration

**待 ratify 决策（开工前拍）**：
1. **lifecycle hook 是否纳入 V4** —— 看 V1-V3 跑下来 hook 改动频率：>1/月 纳入，<1/月 推 V5。**等 V1-V3 数据**
2. **OptimizableSurface 接口的具体方法签名** —— 等 V2 SkillSurface + V3 PromptSurface 真接入跑过后才知道接口长啥样（**不锁，开工时按 V2/V3 实际经验抽**）
3. **AbstractAbEvalRunner Template Method hook 顺序** —— 等 V2 V3 完成时实际 service 长啥样再抽（**不锁**）
4. **canary 互斥**：V2 限制"同 agent 1 个 surface canary" 是否保留？—— V4 推荐保留（防 confounding），但加新 ratify "同 agent 不同 surface 顺序 canary" 的协调
5. **behavior rule candidate 生成的 LLM model** —— runtime t_agent 配置，不锁

**已知 footgun（V1+V2+V3 经验 + 项目）**：
- ⚠️ **过早抽象 = 大失败**：必须等 V2 + V3 真接入跑过才抽 Template Method（V1 plan.md 第四节已明确这点）
- ⚠️ 重构 `SkillAbEvalService` + `AbEvalPipeline` 时**现有测试必须保持绿**（V1 经验：refactor 零行为漂移 + 现有 test 锁）
- ⚠️ `behavior_rules.json` 内置规则库改为 v0 baseline 时，**老 agent.behaviorRules JSON 字段处理**（migration 把现有 customRules 转为 v0 version row？或留向后兼容？）
- ⚠️ V2 `CanaryAllocator` 改泛型时，现有 skill canary 路径不退化
- ⚠️ 现有 N2 `BehaviorRuleRegistry` 是 application-startup 加载内置规则，改 DB-driven active version 不能让启动变慢

**不锁的具体**：
- behavior rule candidate 生成 prompt 工程（看 V1-V3 实际 attribution proposal 形态再写）
- 是否纳入 lifecycle hook
- `OptimizableSurface<V>` 具体方法签名（依赖 V2/V3 实际 service 抽象）

**前置依赖关键检查**：
- V2 + V3 已 commit + dogfood 1-2 周
- V3 attribution agent 跑出 ≥ 1 个真实 behavior_rule proposal（不然 V4 没触发信号）

**验收**：
- 给一个 agent 起 behavior rule canary → 跑完 canary → promote
- 抽象提取后 skill / prompt / behavior rule 三条 A/B 路径**都走同一 `AbstractAbEvalRunner` 骨架**
- 现有 skill / prompt 路径不退化（regression test 全绿）
- V5 user simulator 接入时**只新加一个 surface handler**（不改主框架，验证扩展性）

**预估**：~1500 行重构（核心，跨现有 2 service）+ ~800 行新（behavior rule 全栈）+ ~500 行前端，3-4 周

---

### V5 — 动态用户模拟 + 生产数据反向验证

**档**：Full
**ID**：`EVAL-DYNAMIC-USER-SIM`（已在 backlog）
**前置**：V1-V4 全完成 + V4 OptimizableSurface 抽象已稳定（V5 接入 user sim 作为新 surface eval 维度）
**目标**：把"重新跑一次证明效果比之前好"从"held-out 静态 dataset 上更好"升级为"动态 user simulator + 真生产数据回流都更好"。

**功能范围**（已在 backlog 中描述）：
- 增强 `SessionScenarioExtractorService`：抽 businessGoal / successCriteria / userPersona / userConstraints / failureSignals / expectedOutcome
- 新 system agent `UserSimulatorAgent`：按 goal + persona 动态生成下一轮用户输入
- process-level judge：对完整 transcript 评分
- 把 V2 prod metric backflow + V3 optimization event 接进新 A/B 通过 gate：**A/B 通过 + canary 通过 + 动态 sim 通过 = 真的更好**

**复用清单**：
- ✅ V1 outcome 标签 + pattern → user sim seed scenarios
- ✅ V2 canary metric → 跟 user sim 结果交叉验证
- ✅ V3 optimization event 加 stage=user_sim_verified
- ✅ V4 OptimizableSurface 抽象 → user sim 作为 "eval 维度" 而不是 "surface"，挂在 AbEvalRunner judge 阶段后增强
- ✅ 现有 SessionScenarioExtractorService → 扩字段不重写
- ✅ 现有 EvalJudgeTool / multi-turn judge → 扩 process-level judge

**新建清单**：
- 新 system agent `UserSimulatorAgent` + tool
- `SessionScenarioExtractorService` 扩字段 + Flyway migration（t_eval_scenario 加列）
- process-level judge service
- A/B gate 三因子合成：A/B + canary + user_sim

**待 ratify 决策（V5 开工时拍）**：
1. **user simulator 成本控制**：每个 candidate 跑多少 trial？随机 persona vs fixed？
2. **是否参与 auto-promote**：dynamic sim 通过是否进 auto_promote_after_ab 默认 true 路径？或仍要人工 publish？
3. **multi trial 平均**：simulator 非确定，>1 trial 取均值 / 取最差？
4. **是否纳入 tau-bench**：v3 backlog 提过，跟 user sim 重叠度？

**已知 footgun**：
- ⚠️ user simulator agent 也是 system agent → owner_id=1 + ScheduledTask 模式（V1 经验）
- ⚠️ 动态对话**很贵**（user sim agent 跑完 N 轮，候选 agent 也跑 N 轮，process judge 1 轮 = 2N+1 LLM call/trial）→ cost budget 必须显式
- ⚠️ user sim 输出不能影响生产数据（写专门 t_session.origin='user_sim'）

**预估**：3-4 周，按 backlog 描述

---

### V6（押后，可能永不做）— Tool Registry Versioning

**档**：Full
**ID**：`TOOL-REGISTRY-VERSIONING`
**前置**：V1-V5 全完成 + 实际看到 tool description / output schema 改动**真的需要 A/B**（不是想象需要）

**理由暂不做**：
- Tool 是 Java interface，dynamically instantiate 复杂（不像 prompt text / behavior rule 是字符串）
- AgentLoopEngine runtime 依赖 tool 对象方法调用，sandbox override description 容易但 override execute 困难
- Tool 改动频次低（季度级），A/B ROI 低（一年 4 次 vs prompt 一周 N 次）
- 真有需求时再评估**轻量 description-only A/B 方案**（不动 execute 逻辑，只改 description 文本 + 让 agent 看不同 tool spec 选择是否调用）

**如果真做（V6+ 评估时）**：
- `ToolDescriptionVersionEntity`（仅 description / parameter schema，不动 Tool 接口实现）
- ToolRegistry decorator 模式包装 description override
- 不动 execute / runtime / AgentLoopEngine
- 复用 V4 抽好的 OptimizableSurface 框架

**不锁的具体（V6 真评估时）**：
- 是否做（看实际 tool 改动是否产生 A/B 需求）
- 范围（description-only vs schema vs execute）
- 接入飞轮 vs 单独流程

---

## 六、版本依赖图

```
V0 (SKILL-AB-MULTITURN-FIX, in queue)
   │
   ├──────────┐
   ▼          ▼
   V1 ────► V2 ────► V3 ────► V4 ────► V5
(label/   (skill   (attrib   (multi-   (dynamic
 cluster) canary)   agent)   surface)   user sim)
```

- V1 和 V0 可并行（依赖不同代码区域）
- V2 依赖 V0（skill A/B 多轮）+ V1（outcome 标签作为指标来源）
- V3 依赖 V1（pattern 是输入）+ V2（canary 是输出）
- V4 依赖 V2（canary 模式）+ V3（attribution 已接好 skill/prompt，需扩 behavior_rule 分支）
- V5 独立项，V1-V4 完成后做

---

## 七、已 Ratify 决策（2026-05-14 完成）

| 点 | 决策 |
|---|---|
| 1. 本期 surface 范围 | skill + prompt + behavior rule；架构层面 `SurfaceType` enum 预留 tool / hook / mcp / other / unclear，后续加 surface 不改主框架 |
| 2. 灰度功能形态 | **能力保留 + 默认一刀切**。`rolloutPercentage` 默认 100 等价现行行为，灰度作 opt-in 模式（多用户阶段才用） |
| 3. canary 起步比例 | 个人 dogfood 阶段一刀切，不走 canary。等多用户时默认 **10% 可配置** |
| 4. Publish 按钮 | V2 加配置 `auto_promote_after_ab`（默认 false = A/B 通过后等人按"发布"按钮，true = 自动 promote） |
| 5. 人工介入点 | **A（attribution proposal 人工 approve）+ C'（publish 按钮）**。canary 升档 D / auto-rollback E 仅在 opt-in 灰度模式下生效 |
| 6. V1 标注 | 用 agent（`session-annotator`）+ SubAgentDispatch 模板，参考 memory-curator。不用单 LLM call |
| 7. 聚类深度 | 简单 bucket，不上 ML / embedding |
| 8. V2 节奏 | V1 跑一周 dogfood 看 attribution 信号对不对，再决定 V2 开工时机。**不并行** |
| 9. 复用优先 | 每版本明确"复用 vs 新建"清单（见第八节） |

## 八、复用 vs 新建清单（按版本）

### V1 — PROD-LABEL-CLUSTER

**复用**：
- `TraceScenarioImportService.java:136-151` 6 reason 检测逻辑 → V1 signal stage **直接调，不重写**
- `LlmTraceRepository` / `LlmSpanRepository` → 所有 trace/span 查询
- `SubAgentRegistry` + `SubAgentTool` + `memory-curator` 模板（`MemoryCuratorBootstrap` + classpath prompt + V69 seed + 4 tool 组织方式）→ V1 `session-annotator` agent 同模式
- **P12 ScheduledTask + V69 memory-curator dogfood 模式**（`t_scheduled_task` 表 + 1 cron + 1 agent + 多 tool orchestrate）→ V1 同款，不写 Spring @Scheduled
- 现有 `Traces.tsx` 深链接（OBS-2）→ Pattern member 跳转目标零改动

**新建**：
- 3 张新表（`t_session_annotation` / `t_session_pattern` / `t_pattern_session_member`）+ **V74 (schema) + V75 (seed t_agent + t_scheduled_task) migration**（V72/V73 已被 multimodal 系列占用）
- Entity / Repository 标准三件套 + `SessionAnnotationService` / `SessionAnnotationSignalService` / `SessionPatternClusterService`
- `SessionAnnotatorBootstrap` + `classpath:session-annotator-system-prompt.md`
- **3 个新 Tool（`DetectSignalAnnotationsTool` / `AnnotateSessionTool` / `RecomputeClustersTool`）** —— 都是薄包装，业务逻辑在 service 层
- **1 个 ScheduledTask seed row**（`session-annotator-hourly`，concurrency_policy='skip-if-running'，default enabled=TRUE）—— 不写独立 cron job 类
- `InsightsController` 2 endpoint
- Dashboard `/insights/patterns` 1 个页面 + 2 个组件
- **`SurfaceType` enum + `OptimizableSurface<V>` 空接口骨架**（V4 才有实现）

详见 `docs/requirements/active/PROD-LABEL-CLUSTER/tech-design.md`。

### V2 — SKILL-CANARY-ROLLOUT

**复用**：
- `SkillAbEvalService` 全套（createAndTrigger / runAbTestAsync / promoteCandidate / manualPromote / delta + candidate rate 阈值）—— 都不动
- `SandboxSkillRegistryFactory.buildSandboxRegistryWithSkills` —— skill sandbox 注入零改动
- `EvalJudgeTool` + `EvalScoreFormula` M4_V2 4 维分数 → canary 指标对比轴直接复用
- 现有 `SkillEntity` 字段（parentSkillId / artifactStatus / version）—— 只加 2 列
- `SkillEvolutionPanel.tsx` / `SkillAbPanel.tsx` —— canary panel 嵌入，不重写
- V1 `outcome` 标签 → canary control vs candidate 组指标对比的来源

**新建**：
- `t_canary_rollout` + `t_canary_metric_snapshot` 表
- SkillEntity 加 2 列（`rolloutStage` + `rolloutPercentage`，默认 100）
- `CanaryAllocator`（运行时分流，挂在 AgentLoopEngine skill 加载入口前）—— **核心文件 = 红灯 Full pipeline**
- `CanaryRolloutService` + `ProdMetricsCollector` hourly cron
- Publish 按钮 + 配置项 `auto_promote_after_ab`

### V3 — ATTRIBUTION-AGENT

**复用**：
- `EvalAnalysisSessionEntity` 模型 → 扩 analysisType enum 加 `PATTERN_LEVEL`，不新建表
- `AnalyzeEvalTaskTool` 写 attributionSummary 模式 → attribution-curator proposal tool 直接 copy
- `SubAgentDispatchService`
- `SkillDraftService.extractFromRecentSessions` → attribution-curator 输出 `surface=skill` 时调它，不开第二条 skill 抽取路径
- `PromptImproverService.startImprovement` → 同上 prompt 分支
- `SkillEvolutionPanel` timeline UI 模式 → optimization event timeline 同款

**新建**：
- `t_optimization_event` 表 + service + Spring `ApplicationEventPublisher`
- `attribution-curator` system agent + 5-6 个 tool
- Dispatcher cron（pattern → attribution 派发）
- Dashboard event timeline 页

### V4 — MULTI-SURFACE-FLYWHEEL

**复用**：
- 现有 `BehaviorRuleRegistry`（N2 已落地）
- `behavior-rules.json` 内置规则库 → baseline v0
- **抽 `AbstractAbEvalRunner` Template Method 收口 `SkillAbEvalService` + `AbEvalPipeline`**（重构现有两个，不是新写第三个）
- V2/V3 建好的 canary / event / dashboard 框架

**新建**：
- `t_behavior_rule_version` + `t_behavior_rule_ab_run`
- `BehaviorRuleImproverService`（复用 PromptImproverService 同款 LLM call 模式）
- `OptimizableSurface<V>` 实现类（V1 接口骨架到此填实）

### V5 / V6

V5 复用 V4 全套 + 现有 `SessionScenarioExtractor`；V6 押后。

---

## 九、本方案的"什么不在范围内"

- **不打算重构 ChatService / SessionService / CompactionService 核心路径**：所有新能力通过新表 + 异步队列接入
- **不打算上 Kafka / RabbitMQ**：项目自有 P12 ScheduledTask + `concurrency_policy='skip-if-running'` 即可满足 hourly batch（V69 dogfood 同款）
- **不打算上 ML 聚类 / embedding-based pattern detection**：V1 简单 bucket 够用，复杂度延后
- **不打算改 Flyway migration 治理**：每个 V 自带 1-2 个 migration
- **不打算并行做 V1+V2**：建议 V1 跑一周 dogfood 收集标注信号再开 V2，否则 V2 的 canary metric 没有可靠 baseline

---

## 十、未解决的问题

> **不在此列**：所有 system agent 的 `llm_model` 字段（session-annotator / attribution-curator / 等）—— 这些是 `t_agent` 标准字段，**用户在 dashboard 直接配置**，不是开发期决策。Migration seed 时填一个合理默认值即可。

### V1 已 ratify（2026-05-14）

- [x] **V1 dispatch 模式**：P12 ScheduledTask（V69 dogfood 同款 1 cron + 1 agent + 3 tool orchestrate），不写 Spring @Scheduled
- [x] **V1 ScheduledTask 频率**：hourly（`0 0 * * * *`）
- [x] **V1 session-annotator 单 invocation 最多标注**：10 session（agent 内 max_loops + DetectSignalAnnotations cap）
- [x] **新 system agent owner 约定**：`owner_id = 1 + is_public = TRUE`；V69 memory-curator 留 `owner_id = NULL` 不动
- [x] **V1 Flyway 版本号**：V74 (schema) + V75 (seed t_agent + t_scheduled_task)（V72/V73 已被 multimodal 系列占用，Phase 1.0 BE-Dev 验证）

### 待 V2 / V3 / V4 拍板

- [ ] V2 同一 agent 只能 1 个 surface canary 的"互斥锁"用 advisory lock 还是 DB unique constraint？
- [ ] V3 一个 pattern 触发 attribution 后，多久内不允许再触发同 pattern（避免重复 spend token）？
- [ ] V4 是否把 lifecycle hook 顺手做了？取决于 V1-V3 实际跑下来 hook 演进频率

---

## 十一、变更记录

- 2026-05-14：claude 初稿（draft）
- 2026-05-14：ratify 决策 + 复用 vs 新建清单嵌入，V1 需求包 PROD-LABEL-CLUSTER 起包
- 2026-05-14：批量 rename labeler→annotator + 锁 V1 ratify（hourly cron / 10/batch / owner_id=1）；术语统一到 SessionAnnotation*
- 2026-05-14：Phase 1.0 完成 + 3 push back 修：(a) V72/V73→V74/V75；(b) dispatch 改 ScheduledTask + 1 agent + 3 tool；(c) V75 INSERT 补 lifecycle_hooks NULL
- 2026-05-14：V1 Phase 1.1-1.5 全部 commit + Phase 2 Review 1 轮对抗（java/ts/db 3 reviewer 并行）+ Judge 主会话升 W2 为 Blocker（PG aborted-tx 静默丢数据）+ BE-Dev 一次修完（ON CONFLICT DO NOTHING RETURNING id）。V1 BE+FE 闭环完成，剩 Phase Final dogfood + delivery-index 归档
- 2026-05-14：V2-V6 升级到 B 级细节（复用 vs 新建清单 + 待 ratify 决策 + 已知 footgun + 不锁的具体 + 前置依赖检查），全部基于 V1 实际经验补强。V2 最详细（最快开工），V3-V4 中等，V5-V6 浅。具体细节等 V1 dogfood 数据 / V2-V3 接入后再 ratify
