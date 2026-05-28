# Tech Design — DREAMING-MEMORY-EXTENSION V1

> 创建：2026-05-26
> 状态：design-draft（开 Plan pipeline 时 2 reviewer 对抗后转 design-approved）
> 关联：[index.md](index.md) / [mrd.md](mrd.md) / [prd.md](prd.md)

## 1. 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│  V1 Scope（本 PR）                                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Phase 1 — 基础设施（XS / 无依赖）                                  │
│  ├─ F2 t_memory_store_snapshot entity + V119                     │
│  ├─ F3 LlmSpanEntity.span_kind 扩 MEMORY_SYNTHESIS               │
│  └─ F4 Audit-Mem-1（无代码，verdict 报告）                          │
│                                                                  │
│  Phase 2 — Memory 能力扩展（M / 触红灯 MemoryClusterer 子系统）        │
│  ├─ M1 LlmMemorySynthesizer.synthesize() 扩签名（+sessions, +instr）│
│  │    └─ 含 1-arg backward-compat overload                       │
│  ├─ M2 SessionTranscriptProvider（新 class，只读 t_session_event）  │
│  ├─ M3 TokenEstimator per-session 截断 + 全局 cap                  │
│  │    └─ MemoryStoreTooLargeException                            │
│  └─ M4 Prompt 三 slot 重写 + prompt-snapshot 测试                  │
│                                                                  │
│  Phase 6 — 测试                                                   │
│  ├─ T1 MemorySynthesisIT 5 case                                  │
│  └─ T2 现有 *Test backfill 新签名                                  │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│  V2 包（留待 V1 ship 后启动，硬指标见 prd.md）                         │
│  ├─ rollback REST API（依 V1 F2 已落 entity）                      │
│  ├─ MemorySynthesisExecutor 独立 thread pool（依 V1 F4 verdict）   │
│  └─ Dream Session Meta-Observability（扩 LlmTraceEntity + WS）     │
└─────────────────────────────────────────────────────────────────┘
```

## 2. 文件级 Hotspot 表（V1 范围）

> 来源：2026-05-26 claude-code-guide Dreaming deep dive（14 hotspot 原始版，本表去重后聚焦 Dreaming V1 8 行）。**每行追溯到 wiki R-AMA-MEM-\* 编号 + Anthropic API 协议要求**。

### Phase 1 — 基础设施

| # | 改动 | 文件 | 类型 | API/协议依据 | 难度 |
|---|---|---|---|---|---|
| F2 | `t_memory_store_snapshot` entity + Flyway V119 | `skillforge-server/src/main/java/com/skillforge/server/entity/MemoryStoreSnapshotEntity.java` + `MemoryStoreSnapshotRepository.java` + `src/main/resources/db/migration/V119__create_t_memory_store_snapshot.sql` | NEW | R-AMA-MEM-2 + Dreaming output 是独立新 store 设计 | XS |
| F3 | `LlmSpanEntity.span_kind` 扩 4-enum 加 `MEMORY_SYNTHESIS` | `skillforge-server/src/main/java/.../entity/LlmSpanEntity.java`（修 enum） + `LlmSpanKindRoundtripTest.java`（roundtrip 测试） | MODIFY | R-AMA-MEM-3 + Dreaming dream session 是 first-class observable | XS |
| F4 | Audit-Mem-1（无代码，输出 docs + verdict） | `docs/requirements/active/2026-05-26-DREAMING-MEMORY-EXTENSION/audit-mem-1-executor-isolation.md`（新加） | NEW | R-AMA-MEM-4 + R-AMA-AUDIT-2（wiki 标"先 verify 再决定"）| XS |

### Phase 2 — Memory 能力扩展

| # | 改动 | 文件 | 类型 | API/协议依据 | 难度 |
|---|---|---|---|---|---|
| M1 | `LlmMemorySynthesizer.synthesize()` 扩签名（+sessions, +instructions） + 1-arg backward-compat overload | `skillforge-server/src/main/java/com/skillforge/server/service/memory/LlmMemorySynthesizer.java` | MODIFY | R-AMA-MEM-5 + R-AMA-MEM-1 + Dreaming inputs[] (memory_store + sessions[]) + instructions ≤4096 | M |
| M2 | 新 `SessionTranscriptProvider` + `TranscriptConfig` ConfigurationProperties | `skillforge-server/src/main/java/.../memory/SessionTranscriptProvider.java` + `TranscriptConfig.java` + `SessionTranscriptProviderImpl.java` | NEW | R-AMA-MEM-5 + Dreaming sessions[] 输入数据源 | M |
| M3 | `TokenEstimator` 加 `estimateSessionBatch()` per-session 截断 + 全局 cap | `skillforge-core/src/main/java/com/skillforge/core/compact/TokenEstimator.java`（加 method） + 新 `MemoryStoreTooLargeException` | MODIFY | R-AMA-MEM-5 + Dreaming `input_memory_store_too_large` 错误语义 | S |
| M4 | Prompt 重写：`<memory_clusters>` + `<sessions>` + `<instructions>` 三 slot + prompt-snapshot 测试 | `LlmMemorySynthesizer.java`（修 prompt template） + `LlmMemorySynthesizerPromptSnapshotTest.java`（新） | MODIFY | R-AMA-MEM-1 + Dreaming instructions ≤4096 + slot 位置（参考 wiki 推断 `<instructions>` 额外块） | S |

### Phase 6 — 测试

| # | 改动 | 文件 | 类型 | 难度 |
|---|---|---|---|---|
| T1 | `MemorySynthesisIT` 5 case | `skillforge-server/src/test/.../memory/MemorySynthesisIT.java` | NEW | M |
| T2 | 现有 `MemoryClustererTest` / `LlmMemorySynthesizerTest` backfill | 修 2 文件 | MODIFY | S |

### V2 包预告（不在 V1 PR）

| # | 改动 | 文件 |
|---|---|---|
| V2-R1 | rollback REST API | `MemoryStoreRollbackService.java` + `MemoryStoreController.java` + 加 `POST /api/memory-stores/:owner/rollback?to=:snapshot_id` |
| V2-E1 | `MemorySynthesisExecutor` 独立 thread pool（**依 V1 F4 verdict**） | `skillforge-server/.../config/AsyncExecutorConfig.java` 加 `@Bean("memorySynthesisExecutor")` |
| V2-S1 | Dream Session Meta-Observability | 扩 `LlmTraceEntity` 加 `is_memory_synthesis_pipeline` flag + `SessionEventHandler` relay synthesis events to operator WebSocket |

## 3. 关键技术决策（D1-D6 + 新增 D7-D10）

| # | 决策 | 选 | 理由 |
|---|---|---|---|
| **D1** | 范围 | Dreaming only（Outcomes 拆 `backlog/OUTCOMES-RUBRIC-FOUNDATION/`）| 用户明确要求；不同子系统 ship 风险隔离 |
| **D2** | V1/V2 切分 | V1 = Phase 1+2+6 / V2 = rollback API + executor 隔离 + stream observability | V1 是能力扩展核心；V2 是收益增量功能 |
| **D3** | M1 backward compat | 加 1-arg overload 转发到 3-arg with empty + null | 旧 caller 零改动 ship；`MemoryConsolidator` daily 03:30 调度不动 |
| **D4** | M3 超 budget 行为 | 抛 `MemoryStoreTooLargeException`，不静默截断 | 跟 Anthropic Dreaming `input_memory_store_too_large` 一致；用户自己控制 batch |
| **D5** | F2 V1 只建 entity 不做 rollback API | V1 ship 先有 audit log，rollback controller 留 V2 | 减小 V1 PR 范围 |
| **D6** | F4 audit V1 必做 | V1 audit + verdict 报告；不动 code 落 thread pool | 数据驱动 V2 决策，避免盲目隔离浪费工作 |
| **D7** | M1 `instructions` 参数命名 | `instructions`（跟 API 一致）非 `synthesisInstructions` | method context 已说明是 synthesis；不冗余前缀 |
| **D8** | M2 数据源 | 读 `t_session_event` 非 `t_session_message` | event 表有完整 LLM call 记录；message 表只是 user/assistant turn；message 表读会触 `identity-column-on-rewrite` 风险，event 表干净 |
| **D9** | F2 `t_memory_store_snapshot.store_owner_id` | VARCHAR(64) 字符串包 user_id + agent_id 两种 | 跟 `t_memory.user_id`/`agent_id` 对齐；V2 加 FK 时再切 |
| **D10** | F3 enum 扩 startup safe | JPA `@Enumerated(EnumType.STRING)` 不会 fail；新加 enum 不动旧行 | grep 现有 enum 用法 + roundtrip test 验证 |

## 4. 替代方案（被否决的）

### Alt-1：M2 读 `t_session_message` 替代 `t_session_event`

**否决理由**：`t_session_message` 是 `SessionService.rewriteMessages` 的目标表，有 [identity-column-on-rewrite](../../../../.claude/rules/identity-column-on-rewrite.md) Iron Law 约束；M2 只是只读不写，但读路径可能触发 lazy load + 触发 row 锁，影响 production loop；`t_session_event` 有完整 LLM call 记录，更适合 dreaming "session transcript"语义。

### Alt-2：M1 不动签名，新加 `LlmMemoryDreamer` class

**否决理由**：Dreaming "memory + sessions" 本质就是 memory synthesis 的扩展输入，不是新 capability；新 class 制造概念重复 + 测试矩阵爆炸 + DI graph 多一层；走 backward-compat overload 更简洁。

### Alt-3：F4 跳过 audit 直接 V1 落 `MemorySynthesisExecutor`

**否决理由**：盲目隔离可能浪费工作（现状可能 OK），也可能掩盖问题（隔离后某些 SLA 反而下降）；audit 是 15 分钟成本换 V2 准确决策。

### Alt-4：M3 静默截断超 cap 的 session（不抛异常）

**否决理由**：静默截断让 caller 不知道 input 不完整，输出 memory 质量下降但无报警；跟 Anthropic Dreaming `input_memory_store_too_large` 设计哲学一致 — 让 caller 显式控制 batch size。

### Alt-5：F2 V1 直接做 rollback API（不留 V2）

**否决理由**：rollback API 涉及 service + controller + 权限 + 测试，扩 V1 PR 范围；V1 先 ship entity 让生产先有 snapshot 数据 1-2 周，V2 rollback API 落地时已有真实 dogfood 数据驱动 UX 设计。

## 5. 实现拆分（Sprint 计划）

| Sprint | Phase | 子项 | 工作量 | 触红灯 | 必跑 reviewer |
|---|---|---|---|---|---|
| **Sprint 1**（~3-4 天）| Phase 1 | F2 + F3 + F4 | ~3 天 BE | 否 | java-reviewer + database-reviewer (F2 entity + V119) |
| **Sprint 2**（~1-2 周）| Phase 2 | M1-M4 | ~7-10 天 BE | **是** — 触碰 `MemoryClusterer` 子系统 + `LlmMemorySynthesizer` 子系统 | java-reviewer + java-design-reviewer (M2 新 class) + compact-reviewer (M3 触 TokenEstimator + persistence-shape-invariant) + 必须 prompt-snapshot 测试 |
| **Sprint 3**（~3 天）| Phase 6 | T1 + T2 | ~3 天 | 否 | java-reviewer + database-reviewer (T1 IT 涉 DB) |

**总 V1 ~2-3 周**。

## 6. 风险

| ID | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | M4 prompt 重写让 `LlmMemorySynthesizer` 现有 prompt 字节漂移，触发 [persistence-shape-invariant](../../../../.claude/rules/persistence-shape-invariant.md) | 中 | 中 | M4 必加 `LlmMemorySynthesizerPromptSnapshotTest`；Plan 时让 `compact-reviewer` 显式审 |
| R2 | M2 `SessionTranscriptProvider` 读 `t_session_event` 时不小心触发 lazy load 触 row 锁 | 低 | 中 | M2 用 native query + read-only transaction；明示**只读**注释 + reviewer 显式审 |
| R3 | F4 audit 发现 ❌（synthesis 确实跟 production loop 抢资源）—— V2 紧急升 P1 | 中 | 中 | F4 是 Sprint 1 优先做；audit 结果 ❌ → 立刻在 V2 包补 thread pool；不影响 V1 ship |
| R4 | M1 backward-compat 1-arg overload 让现有 caller 没意识到能用新功能，dogfood 不动 | 中 | 低 | Sprint 2 ship 后 `MemoryConsolidator` daily 03:30 调度先**手动 dry-run** 用 3-arg；观察 1 周再切默认 |
| R5 | F3 enum 扩值 startup fail（JPA `@Enumerated(EnumType.STRING)` 不会，但小概率有自定义 deserialize 代码）| 低 | 中 | F3 必加 `LlmSpanKindRoundtripTest`；Sprint 1 ship 前手动 startup 验证 |
| R6 | T1 IT 真活跑 `SessionTranscriptProvider` 需要 mock LLM provider — 测试时间长 | 中 | 低 | 用 SkillForge 现有 `MockLlmProvider` fixture；IT 只跑 1 case 真活，其他 4 case 走 mock |
| R7 | F2 snapshot 表 `content_hash` 计算耗时（大 store 时 SHA256 几百 ms） | 低 | 低 | snapshot 是 fire-and-forget，跑批前异步算；超时 fallback 用 SHA256 of memory_ids only |
| R8 | M3 `MemoryStoreTooLargeException` 触发时 snapshot 已经写入但 LLM 没调 → snapshot 孤儿 | 低 | 低 | 接受：snapshot 孤儿是 audit log 数据本身，不算 bug；V2 rollback API 时加 "skip orphan snapshot" 逻辑 |
| R9 | M2 `t_session_event` 表大（prod 千万级行）读多次 → DB 压力 | 中 | 中 | M2 走 `LIMIT maxEventsPerSession` + 加 index on `(session_id, created_at desc)` 已存在；Plan 时让 database-reviewer 显式审 |

## 7. 测试计划

### 单元测试（Sprint 1-3 各 phase 内做）

- F2: `MemoryStoreSnapshotRepositoryTest` — CRUD + content_hash 非空 + JSONB roundtrip
- F3: `LlmSpanKindRoundtripTest` — enum 序列化 / 反序列化
- F4: 无（pure docs，audit 报告本身）
- M1: `LlmMemorySynthesizerTest` 加 3-arg method case + 1-arg overload backward-compat case
- M2: `SessionTranscriptProviderTest` — chunk size / overlap / role filter / maxEventsPerSession 边界
- M3: `TokenEstimatorTest` 加 per-session 截断 + cap 边界 + `MemoryStoreTooLargeException` 触发条件
- M4: `LlmMemorySynthesizerPromptSnapshotTest` — 防字节漂移（断言 3 case：null instructions / empty sessions / 完整 3 slot 各对应一个 snapshot file）

### Integration 测试（Sprint 3 T1 集中做）

见 [`prd.md` AC-7 矩阵](prd.md#ac-7---it-覆盖矩阵)。

### E2E 验证（Phase Final 做）

- 跑 1 个真活 dogfood：拿 prod 一个 5+ active session 但 0 reflection 的用户/agent，跑 `LlmMemorySynthesizer.synthesize(clusters, sessions, "focus on coding-style preferences")` → DB 验：
  - `t_memory` 至少 +1 行 reflection
  - `t_llm_span` 至少 +1 行 `MEMORY_SYNTHESIS`
  - `t_memory_store_snapshot` 至少 +1 行 + content_hash 非空
  - audit log（原 sessions content + 原 memory）跑批前后**字节不变**

## 8. 依赖检查清单（开 Plan 时复跑）

- [ ] M1 `LlmMemorySynthesizer.synthesize()` 现有 caller 列表（grep `synthesize\(` in `skillforge-server`）— 确认 backward-compat overload 真覆盖
- [ ] M2 `SessionTranscriptProvider` 数据源 `t_session_event` schema 不动（V1 范围）
- [ ] M2 native query 走 read-only transaction（grep `@Transactional(readOnly=true)`）
- [ ] M3 `TokenEstimator` 是 `skillforge-core` 模块文件 — Sprint 2 改动跨模块，注意 pom 不引新依赖
- [ ] F3 `LlmSpanEntity.span_kind` enum 扩值 — verify 现有 `@Enumerated(EnumType.STRING)` 不会 startup fail（实际 JPA 不会，但 grep 确认无自定义 converter）
- [ ] F4 audit 走 grep + 调用链 + 一段 Read 关键文件 — 走 [`systematic-debugging.md`](../../../../.claude/rules/systematic-debugging.md) Phase 1 5 层取证模板，不靠脑补

## 9. 失败回滚预案

- Sprint 1 任何子项失败 → revert 该 PR，下个 sprint 不动
- Sprint 2 M1-M4 ship 后 dogfood 7 天无新 memory 产出 → 不算 V1 失败，标 "dogfood ROI 待观察" 进 delivery-index；V2 packaging 时增加 prompt 迭代 task
- Sprint 2 M4 prompt-snapshot 测试在 PR 时挂红 → 必修 prompt 让 snapshot 通过；不允许 update snapshot 跳测
- F4 audit verdict ❌（synthesis 确实抢资源）+ Sprint 2 已 ship → V2 紧急升 P1 补 `MemorySynthesisExecutor`；V1 本身不 revert（能力扩展 ROI > 资源抢占短期影响）

## 10. 关联

- wiki [`harness/anthropic-managed-agents.md`](../../../../../research-docs/research/agent-harness-wiki/harness/anthropic-managed-agents.md) — Ground truth：5 R-AMA-MEM-\* + 20 verified facts
- wiki [`harness/skillforge.md`](../../../../../research-docs/research/agent-harness-wiki/harness/skillforge.md) — Ground truth：SkillForge 现有 100+ verified facts
- [`mrd.md`](mrd.md) — 用户原话 + 5 痛点 + Q1-Q5 待澄清
- [`prd.md`](prd.md) — 验收点 AC-1 ~ AC-7 + FR-1 ~ FR-4
- [`index.md`](index.md) — sprint 划分 + V2 包预告
- 姊妹包 [`backlog/OUTCOMES-RUBRIC-FOUNDATION/`](../../backlog/OUTCOMES-RUBRIC-FOUNDATION/) — Outcomes 独立 ship
- [`pipeline.md`](../../../../.claude/rules/pipeline.md) — Phase 2 触红灯必走 Full pipeline
- [`persistence-shape-invariant.md`](../../../../.claude/rules/persistence-shape-invariant.md) — M4 prompt 改写风险点
- [`identity-column-on-rewrite.md`](../../../../.claude/rules/identity-column-on-rewrite.md) — M2 `SessionTranscriptProvider` 只读边界（不读 `t_session_message`）
