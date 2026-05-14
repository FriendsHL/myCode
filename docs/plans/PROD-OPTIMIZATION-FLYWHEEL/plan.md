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

### V2 — Skill 闭环最后一公里：灰度 + 生产指标回流

**档**：Full（触碰 AgentLoopEngine 核心 + 加 schema）
**ID**：`SKILL-CANARY-ROLLOUT`
**前置**：V0 完成（skill A/B 多轮跑通）+ V1 完成（生产标注已经有 outcome 标签可对比）
**目标**：飞轮第⑦⑧⑨步对 skill 这一条 surface 落地。

**功能范围**：
- `t_canary_rollout` + `t_canary_metric_snapshot` 表
- SkillEntity 加列：`rolloutStage`（disabled / canary / production / rolled_back）+ `rolloutPercentage`
- `CanaryRolloutService`：启停 canary、调整 percentage、auto-rollback signal
- `CanaryAllocator`：sessionId hash % percentage → 决定本次 session 用 active 还是 candidate skill
- 改 AgentLoopEngine / SkillRegistry 路径：按 allocator 结果挂载 skill（**红灯 + Full pipeline**）
- `ProdMetricsCollector`：hourly cron 聚合 t_session_annotation（control 组 vs candidate 组的 outcome 标签），写 t_canary_metric_snapshot
- Dashboard skill 详情页加 canary panel：rollout gauge / 24h 指标 / promote 按钮 / rollback 按钮
- Auto-rollback：candidate fail_rate / control fail_rate > 1.5 且样本 > 50 触发，立刻 percentage=0

**已 ratify 决策（建议）**：
1. 同一 agent 同时只能有 1 个 surface 在 canary（V2 仅 skill，互斥保留给 V4）
2. canary 默认起步 5%；用户手动调档 5/20/50/100
3. session 一旦进 canary 组，整个 session 生命周期用同一版本（不中途切）
4. auto-rollback 触发后必须人工 reset 才能再起 canary（防 oscillation）

**不做**：
- 不接 attribution agent（V3）
- 不做 prompt / behavior rule 的 canary（V4）

**验收**：
- 把一个 skill 上 canary 5% → 24h → 指标看板能看到 control vs candidate
- 人工触发 promote → percentage 100% + active 切换
- 人工注入坏 candidate → auto-rollback 触发 + dashboard 告警
- 现有 SkillAbEvalService promoteCandidate 全量路径不退化（rolloutPercentage 默认 100）

**预估**：~1500 行后端 + ~600 行前端 + Flyway 2 个 migration，2-3 周

---

### V3 — Attribution Agent + Optimization Event 因果链

**档**：Full（新 system agent + SubAgent 集成 + 新表）
**ID**：`ATTRIBUTION-AGENT`
**前置**：V1 完成（pattern 可读）+ V2 完成（canary 可写 metrics）
**目标**：飞轮第③⑤⑥步打通自动化通路：从 pattern → "改哪个 surface 改成什么" → 自动起 candidate → 自动发起 A/B + canary。

**功能范围**：
- `t_optimization_event`（因果链表）
- 新 system agent `attribution-curator`（参考 memory-curator 模式）：
  - 有 tool：`SessionAnnotationRead` / `PatternRead` / `SessionMessageRead` / `TraceRead` / `ProposeOptimization` / `WriteOptimizationEvent`
  - prompt 工程：让它读 pattern + 抽样 session → 输出"改哪个 surface（skill/prompt/rule）+ 怎么改 + 期望效果"的结构化 proposal
- `AttributionDispatcher`：cron / 手动触发，对一个 pattern 派发一次 attribution-curator
- 接入现有 candidate 生成器：
  - surface=skill → trigger SkillDraftService
  - surface=prompt → trigger PromptImproverService
  - surface=behavior_rule → 暂留 stub（V4 才有 generator）
- 起完 candidate 自动发起 A/B run，A/B 通过自动发起 canary（接 V2）
- 每个阶段转换写 t_optimization_event
- Dashboard：optimization event 时间轴页（pattern → attribution → candidate → ab → canary → verified 的链路视图）

**已 ratify 决策（建议）**：
1. attribution-curator 默认不全自动 promote（"产出 proposal → 人工 review 通过才起 candidate"），P2 再考虑全自动
2. 一个 pattern 只能起一次 active optimization event（防重复占用 candidate quota）
3. 跨 surface 同时 propose（同一 pattern 同时建议改 skill 和 prompt）保留为 V5 议题

**不做**：
- 不做 behavior rule 的 candidate generator（V4）
- 不做 user simulator 多轮 prove-better（V5）

**验收**：
- 给一个真实 pattern 跑 attribution-curator → 输出可读 proposal
- proposal 被 approve 后能自动串完 candidate → A/B → canary → verified 全链路
- t_optimization_event 时间轴 dashboard 能完整还原一次飞轮跑

**预估**：~1200 行后端（agent prompt + 6 个新 tool + dispatcher）+ ~500 行前端，3 周

---

### V4 — Behavior Rule + Lifecycle Hook 纳入飞轮

**档**：Full（多 surface 统一抽象 + 改两个核心配置路径）
**ID**：`MULTI-SURFACE-FLYWHEEL`
**前置**：V2 + V3 完成（skill 这一路完整闭环 → 已验证模式）
**目标**：把 behavior rule（必做）和 lifecycle hook（视使用率决定）接入同一飞轮。

**功能范围**：
- 新表 `t_behavior_rule_version` + `t_behavior_rule_ab_run`，类比 PromptVersionEntity 模式
- 新 service `BehaviorRuleImproverService`：candidate 生成（LLM 写规则文本）
- 新 service `BehaviorRuleAbEvalService` 或 generic `AbEvalRunner<BehaviorRule>`
- 抽 `OptimizableSurface<V>` 接口：SkillAbEvalService / AbEvalPipeline / BehaviorRuleAbEvalService 共用骨架
- 抽 `AbEvalRunner<V>` 把三处 sandbox 注入 + judge + 阈值 promote 收口
- BehaviorRuleRegistry 改 active version 查表（active_version_id 字段加到 AgentEntity 或独立表）
- CanaryAllocator 扩 surface 维度（V2 只支持 skill）
- AttributionAgent 的 ProposeOptimization tool 接 behavior_rule 分支
- Dashboard：behavior rule 详情页加 canary panel（复用 V2 组件）

**可选纳入（视用户使用频率决定）**：
- Lifecycle Hook 同样套路（LifecycleHookVersionEntity / LifecycleHookAbRunEntity / LifecycleHookImproverService）

**已 ratify 决策（建议）**：
1. tool 不纳入（重构成本太高，单独 V6+）
2. 同 agent 同时只能 1 个 surface canary 的限制保留
3. OptimizableSurface 抽象**只在 V4 真有第二个 surface 时落地**（不提前抽）

**不做**：
- 不做 tool registry versioning
- 不做 prod metric 反向 train tagger（V5）

**验收**：
- 给一个 agent 起 behavior rule canary → 跑完 canary → promote
- 抽象提取后 skill / prompt / behavior rule 三条 A/B 路径都走同一 AbEvalRunner
- 现有 skill 和 prompt 路径不退化

**预估**：~1500 行重构 + ~800 行新 + ~500 前端，3-4 周

---

### V5 — 动态用户模拟 + 生产数据反向验证

**档**：Full
**ID**：`EVAL-DYNAMIC-USER-SIM`（已在 backlog）
**前置**：V1-V4 全完成
**目标**：把 "重新跑一次证明效果比之前好" 从"在 held-out 静态 dataset 上更好"升级为"在动态 user simulator + 真生产数据回流上都更好"。

**功能范围**（已在 backlog 中描述）：
- 增强 SessionScenarioExtractorService：抽 businessGoal / successCriteria / userPersona / userConstraints / failureSignals / expectedOutcome
- 新 system agent `UserSimulatorAgent`：按 goal + persona 动态生成下一轮用户输入
- process-level judge：对完整 transcript 评分
- 把 prod metric backflow 接进 A/B 通过 gate：A/B 通过 + canary 通过 + 动态 sim 通过 = "真的更好"

**已 ratify 决策**：留至 V5 真开工时决定（动态模拟成本控制 / 是否参与 auto-promote / 多 trial 平均等）

**预估**：3-4 周，按 backlog 描述

---

### V6（押后，可能永不做）— Tool Registry Versioning

**档**：Full
**ID**：`TOOL-REGISTRY-VERSIONING`
**理由暂不做**：
- Tool 是 Java interface，dynamically instantiate 复杂
- AgentLoopEngine runtime 依赖 tool 对象方法调用，sandbox override description 容易但 override execute 困难
- Tool 改动频次低（季度级），A/B ROI 低
- 真有需求时再评估"轻量 description-only A/B"方案

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
- `LlmMemorySynthesisScheduler` 模板 → V1 三个 hourly cron 照抄
- 现有 `Traces.tsx` 深链接（OBS-2）→ Pattern member 跳转目标零改动

**新建**：
- 3 张新表（`t_session_annotation` / `t_session_pattern` / `t_pattern_session_member`）+ V72 + V73 migration
- Entity / Repository / Service 标准三件套
- `SessionAnnotatorBootstrap` + `classpath:session-annotator-system-prompt.md`
- 2 个新 Tool（`SessionFetchTool` / `SessionAnnotateTool`）
- 3 个 cron job（`SignalAnnotationJob` / `LlmAnnotationJob` / `PatternClusteringJob`）
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
- **不打算上 Kafka / RabbitMQ**：cron + Postgres advisory lock 即可满足 hourly batch
- **不打算上 ML 聚类 / embedding-based pattern detection**：V1 简单 bucket 够用，复杂度延后
- **不打算改 Flyway migration 治理**：每个 V 自带 1-2 个 migration
- **不打算并行做 V1+V2**：建议 V1 跑一周 dogfood 收集标注信号再开 V2，否则 V2 的 canary metric 没有可靠 baseline

---

## 十、未解决的问题

> **不在此列**：所有 system agent 的 `llm_model` 字段（session-annotator / attribution-curator / 等）—— 这些是 `t_agent` 标准字段，**用户在 dashboard 直接配置**，不是开发期决策。Migration seed 时填一个合理默认值即可。

### V1 已 ratify（2026-05-14）

- [x] **V1 三个 cron 频率：hourly**
- [x] **V1 session-annotator 单批 10 session / batch**（agent 内 max_loops 限制）
- [x] **新 system agent owner 约定**：`owner_id = 1 + is_public = TRUE`；V69 memory-curator 留 `owner_id = NULL` 不动

### 待 V2 / V3 / V4 拍板

- [ ] V2 同一 agent 只能 1 个 surface canary 的"互斥锁"用 advisory lock 还是 DB unique constraint？
- [ ] V3 一个 pattern 触发 attribution 后，多久内不允许再触发同 pattern（避免重复 spend token）？
- [ ] V4 是否把 lifecycle hook 顺手做了？取决于 V1-V3 实际跑下来 hook 演进频率

---

## 十一、变更记录

- 2026-05-14：claude 初稿（draft）
- 2026-05-14：ratify 决策 + 复用 vs 新建清单嵌入，V1 需求包 PROD-LABEL-CLUSTER 起包
- 2026-05-14：批量 rename labeler→annotator + 锁 V1 ratify（hourly cron / 10/batch / owner_id=1）；术语统一到 SessionAnnotation*
