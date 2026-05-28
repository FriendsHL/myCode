# PRD — DREAMING-MEMORY-EXTENSION V1

> 创建：2026-05-26
> 状态：prd-draft（开 Plan pipeline 时 ratify D1-D6 决策 + Q1-Q5 澄清后转 prd-ready）

## 目标

让 SkillForge `LlmMemorySynthesizer` 从 **memory → memory**（reorganize 已沉淀 memory）升级到 **memory + sessions[] → memory**（从 session transcript 直接挖未观测 pattern），借鉴 Anthropic Managed Agents Dreaming 的设计，**不引入 Anthropic 闭源 SaaS 依赖**。

V1 ship 3 件事：

1. **Memory 能力扩展**：`LlmMemorySynthesizer.synthesize()` 加 `sessions[]` + `instructions` 两参数；新 `SessionTranscriptProvider` 拉转录；`TokenEstimator` cap 防爆；prompt 三 slot 重写
2. **基础设施 + dogfood observability**：`t_memory_store_snapshot` entity（V1 只建表先有 audit log，rollback API 留 V2）；`LlmSpanEntity.span_kind` 扩 `MEMORY_SYNTHESIS`（让 synthesis 跑批成 first-class trace span）
3. **Audit 决策依据**：F4 grep + 调用链 verify `MemorySynthesisExecutor` 是否真跟 production loop 抢资源；写 verdict 报告作 V2 决策依据

## 非目标（V1 不做）

- ❌ rollback REST API（`POST /api/memory-stores/:owner/rollback?to=:snapshot_id`）— V1 只建 entity 落 audit log，rollback controller 留 V2 包
- ❌ `MemorySynthesisExecutor` 独立 thread pool 落地 — V1 只 audit，V2 看 verdict 决定（D6）
- ❌ Dream Session Meta-Observability（让 synthesis 跑批自身成为可 stream session）— V2 包
- ❌ Outcomes 相关任何能力（用户明确拆出去到 `backlog/OUTCOMES-RUBRIC-FOUNDATION/`）
- ❌ Multiagent 相关任何能力（wiki 已结论不学）
- ❌ 引入 Anthropic Managed Agents SDK / client 依赖

## 工作流

### Phase 1 — 基础设施（XS / 无依赖 / 3 子项）

**F2**: 跑 M1 前 → `INSERT t_memory_store_snapshot` 记录当前 store hash → 跑批失败可手动查表对照（rollback API 留 V2）

**F3**: 每次 `LlmMemorySynthesizer.synthesize()` 跑批 → emit 1 个 `LlmSpanEntity(span_kind=MEMORY_SYNTHESIS)`，含 input cluster count / session count / instructions hash / output proposal count / duration / cost

**F4**: Audit（无代码改动）— grep `MemorySynthesisExecutor` / `@Async` annotation / `taskExecutor` / `MemoryConsolidationScheduler` → 追调用链确认 synthesis 跑批走哪个 thread pool → 跟 production loop（`AgentLoopEngine` 主请求线程）是否共用 pool → 写 verdict 报告 (✅ 不抢 / ⚠️ 偶尔 / ❌ 确认抢)

### Phase 2 — Memory 能力扩展（M / 触红灯 `MemoryClusterer` 子系统 / 4 子项）

**M1**: `LlmMemorySynthesizer.synthesize(clusters, sessions: List<SessionId>, instructions: String)` 签名扩展；保留 1-arg `synthesize(clusters)` overload 转发到 3-arg with `(clusters, List.of(), null)` 保 backward compat

**M2**: 新 `SessionTranscriptProvider` — 从 `t_session_event` 拼接转录 chunks；走 `@ConfigurationProperties("skillforge.memory.transcript")` 暴露 `chunkSize` / `chunkOverlap` / `roleFilter`（默认 `assistant,user`） / `maxEventsPerSession`

**M3**: `TokenEstimator` 加 `estimateSessionBatch(sessions, perSessionCap, globalCap)` → 超 cap 抛 `MemoryStoreTooLargeException`（不静默截断）— 跟 Anthropic Dreaming `input_memory_store_too_large` 错误语义一致

**M4**: Prompt 重写 — 现有 `<memory_clusters>` slot + 新 `<sessions>` slot + 新 `<instructions>` slot（null instructions 时整 slot 不渲染）；配合 `LlmMemorySynthesizerPromptSnapshotTest` 防字节漂移触发 [persistence-shape-invariant](../../../../.claude/rules/persistence-shape-invariant.md)

### Phase 6 — 测试 + 集成

**T1**: 新 `MemorySynthesisIT` 5 case（见 [AC-7 矩阵](#ac-7---it-覆盖矩阵)）

**T2**: 现有 `MemoryClustererTest` / `LlmMemorySynthesizerTest` backfill 加新签名调用 + 旧签名向后兼容 case

## 功能需求

### FR-1 — `t_memory_store_snapshot` entity

| 字段 | 类型 | 约束 | 语义 |
|---|---|---|---|
| `snapshot_id` | UUID | PK | 唯一标识 |
| `store_owner_id` | VARCHAR(64) | NOT NULL | 跟现有 `t_memory.user_id`/`agent_id` 对齐（V1 用 owner_id 字符串包两种） |
| `taken_at` | TIMESTAMPTZ | NOT NULL DEFAULT now() | snapshot 时间 |
| `source_run_id` | UUID | NULL | 关联到触发 snapshot 的 synthesis run（M1 跑批生成）|
| `memory_ids_json` | JSONB | NOT NULL | 该 snapshot 时刻 store 含的 memory_id list |
| `content_hash` | VARCHAR(64) | NOT NULL | SHA256 of `memory_ids + each memory's content hash`（用于后续 verify 字节不变）|

V1 没 controller；V2 加 `POST /api/memory-stores/:owner/rollback?to=:snapshot_id`。

### FR-2 — `LlmSpanEntity.span_kind` enum 扩展

```java
public enum SpanKind {
    LLM,
    TOOL,
    EVENT,
    MEMORY_SYNTHESIS  // 新加
}
```

每次 `LlmMemorySynthesizer.synthesize()` 跑批 emit 1 个 `MEMORY_SYNTHESIS` span，含：
- `input_cluster_count`: int
- `input_session_count`: int（0 = backward-compat 旧 caller / N≥1 = 新 Dreaming 路线）
- `instructions_hash`: SHA256(instructions) or null
- `output_proposal_count`: int
- `duration_ms`: long
- `cost_usd`: BigDecimal

写 enum 扩展前 verify 现有 `@Enumerated(EnumType.STRING)` 不会 startup fail（DB 没历史 `MEMORY_SYNTHESIS` 值，新加 enum 不动旧行）。

### FR-3 — `LlmMemorySynthesizer.synthesize()` 新签名

```java
// V1 之前
public List<MemoryProposal> synthesize(List<MemoryCluster> clusters);

// V1 新（3-arg 是主路径）
public List<MemoryProposal> synthesize(
    List<MemoryCluster> clusters,
    List<SessionId> sessions,         // 0-100, 可为 empty list（向后兼容旧 caller）
    @Nullable String instructions     // ≤4096 char, null = 默认行为不渲染 instructions slot
);

// 1-arg backward compat overload
public List<MemoryProposal> synthesize(List<MemoryCluster> clusters) {
    return synthesize(clusters, List.of(), null);
}
```

**实现要点**：
- sessions[] 通过 `SessionTranscriptProvider` 拉转录，超 token budget **整批 fail** with `MemoryStoreTooLargeException`（不静默截断 single session）— 跟 Anthropic Dreaming behavior 一致
- instructions null → prompt 不渲染 `<instructions>` slot；非 null → 渲染 + enforce ≤4096 char（BE 主动校验，超长抛 `IllegalArgumentException`）
- **Immutable input invariant**：跑批过程不修改输入的 `clusters` list 元素 / 不修改 `sessions` 对应的 `t_session_event` 行（IT 跑批前后对比断言）
- Pre-run hook：在调 LLM 前 `INSERT t_memory_store_snapshot`（F2）；如果跑批失败，snapshot 仍保留作 audit log

### FR-4 — `SessionTranscriptProvider`

```java
public interface SessionTranscriptProvider {
    /**
     * Pull transcript chunks for given sessions.
     * Read-only — does NOT touch t_session_message rewrite preserve logic.
     */
    List<TranscriptChunk> fetchTranscripts(List<SessionId> sessions);
}

@ConfigurationProperties("skillforge.memory.transcript")
public record TranscriptConfig(
    int chunkSize,            // 默认 2000 char per chunk
    int chunkOverlap,         // 默认 200 char overlap
    Set<MessageRole> roleFilter,    // 默认 {USER, ASSISTANT}
    int maxEventsPerSession   // 默认 100
) {}
```

实现走 `t_session_event` 读取（不读 `t_session_message`），role filter 过滤 + chunk size 切片。**严格只读** — 不调用 `SessionService.rewriteMessages` 路径，不触 `identity-column-on-rewrite` invariant。

## 验收点

见 [`index.md` AC-1 ~ AC-7](index.md#验收点v1待细化)。

### AC-7 — IT 覆盖矩阵

| Case | 输入 | 预期 |
|---|---|---|
| T1.1 | clusters + 5 sessions + instructions | 至少产出 1 个 MemoryProposal；emit 1 `MEMORY_SYNTHESIS` span；snapshot 行写入 |
| T1.2 | clusters only（empty sessions, null instructions）| 行为同 V1 之前；prompt 不含 `<sessions>` / `<instructions>` slot；emit span(session_count=0) |
| T1.3 | clusters + sessions + null instructions | prompt 含 `<sessions>` 不含 `<instructions>`（用 prompt builder spy 断言）|
| T1.4 | clusters + 100 sessions（cap 内）| 不抛异常；snapshot 写入；emit span(session_count=100) |
| T1.5 | clusters + sessions 超 cap | 抛 `MemoryStoreTooLargeException`；**DB 无副作用**：`t_memory` 0 新行 + `t_memory_store_snapshot` 0 新行 + `t_llm_span` 0 新 MEMORY_SYNTHESIS 行 |

## 验证预期

| 阶段 | 验证 |
|---|---|
| Phase 1 F2 落地 | `INSERT t_memory_store_snapshot` 真活能存能查；`content_hash` 非空 SHA256；Flyway V119 apply OK |
| Phase 1 F3 落地 | dummy synthesis 跑批 → `t_llm_span` 真活 +1 行 `MEMORY_SYNTHESIS`（curl 查 `SELECT span_kind FROM t_llm_span WHERE created_at > now() - interval '5 min'`）|
| Phase 1 F4 audit | grep 输出 + 调用链截图 + 三种 verdict 之一 + commit message 标 "audit done verdict=X"；报告落 `docs/requirements/active/2026-05-26-DREAMING-MEMORY-EXTENSION/audit-mem-1-executor-isolation.md` |
| Phase 2 M1-M4 落地 | dogfood：拿 prod 一个 5+ active session 但 0 reflection 的用户/agent，跑 `LlmMemorySynthesizer.synthesize(clusters, sessions, "focus on coding-style preferences")` → DB 验 `t_memory` 至少 +1 行 + `t_llm_span` 至少 +1 `MEMORY_SYNTHESIS` span + audit log 验 input sessions content 字节不变 |
| Phase 6 T1 | 5 IT case 全 PASS；`mvn -pl skillforge-server test -Dtest=MemorySynthesisIT` BUILD SUCCESS |

## 关联文档

- [`mrd.md`](mrd.md) — 用户原话 / 调研来源 / 5 痛点 / 5 限制 / Q1-Q5 待澄清
- [`tech-design.md`](tech-design.md) — 8 条文件级 hotspot / 实现拆分 / 风险 / 测试计划
- [`index.md`](index.md) — 入口 / D1-D6 决策 / sprint 划分

## V2 包预告

- **rollback REST API**：依 V1 F2 已落 entity，V2 加 `POST /api/memory-stores/:owner/rollback?to=:snapshot_id`
- **`MemorySynthesisExecutor` 独立 thread pool**：依 V1 F4 audit verdict 决定（✅ 不做 / ⚠️ 看情况 / ❌ V2 P1 必做）
- **Dream Session Meta-Observability**：让 `synthesize()` 跑批自身成为可 stream session（参考 Anthropic `dream.session_id`），扩 `LlmTraceEntity` + WebSocket relay
- **V2 启动条件硬指标**：V1 dogfood 至少 1 周 + `t_memory` 至少 +10 行 reflection 来自 sessions[] 输入（防 V2 无 evidence 就启动）
