# SKILL-CREATOR-WITH-EVAL 技术方案

---
id: SKILL-CREATOR-WITH-EVAL
status: design-draft
prd: ./prd.md
risk: Mid
mode: full
created: 2026-05-18
updated: 2026-05-18
---

## TL;DR

V1 时 skill-creator skill 已建 SKILL.md (7 步 workflow), 本需求**补齐 evaluation 实施**:

1. `system-skills/skill-creator/` 加 scripts/ (3 file) + evals/ (1 file, 2-3 self-eval case)
2. `SkillCreatorService.evaluateSkillDraft` 新 Java method, SubAgent fork × 2 (with/without) → EvalJudgeTool 判 → benchmark.json + LLM 总结 → t_skill_draft.evaluation_result_json + status
3. V91 migration: t_skill_draft 加 evaluation_result_json + source/status enum 扩
4. 4 入口接入 hook
5. FE SkillDraftDetailDrawer + RejectedSkillDrafts panel

复用现 SkillForge 框架: V1 SubAgent infra / V5 EvalJudgeTool / V6 EphemeralScenarioCleanupService / V6 t_eval_scenario 'ephemeral' status. **0 改动 Iron Law 7+1 BE + 3 FE.**

## 现状 (2026-05-18 grep verify)

### skill-creator skill 现状
```
system-skills/skill-creator/
├── SKILL.md  (4338 bytes, 7 步 workflow 含 eval 概念但未实施)
└── references/
    ├── eval-guide.md  (3.2k - 详细 eval workflow 文档)
    ├── schemas.md  (12k)
    ├── writing-guide.md  (2.6k)
    └── troubleshooting.md  (3.6k)
```

**缺**: `scripts/` 不存在 / `evals/` 不存在 / 实际 eval 不跑.

### 现有 Java
- `SkillCreatorService.java` — 仅 `render(draft, targetDir)` 写 SKILL.md, 零 evaluation
- `SkillDraftService.extractFromRecentSessions(agentId)` — V1 PROD-LABEL-CLUSTER 已有 LLM 抽 pattern → SkillDraft, **跑完不 evaluate**
- `SkillImportService` — V1 SKILL-IMPORT-MVP 上传 + marketplace, **跑完不 evaluate**
- `SubAgentTool` — V1 已有, payload 含 parentSessionId / taskPrompt / agentDef / skillIdsOverride
- `EvalJudgeTool.judgeMultiTurnConversation(transcript, scenario)` — V5 已有, 返 composite/quality/efficiency/latency/cost 5 score
- `EphemeralScenarioCleanupService.cleanupEphemerals(scenarioIds)` — V6 已有 @Transactional(REQUIRES_NEW) cleanup

### 4 入口 controller
- 用户上传: `POST /api/skills/import` SkillImportService
- Marketplace 下载: `POST /api/skills/import-from-marketplace` SkillImportService (复用)
- 自然语言描述: agent attach skill-creator skill (SystemSkillLoader 自动注册), 用户聊 agent 触发
- Extract from sessions: SkillDraftService.extractFromRecentSessions (无 controller, cron / internal call)

## 范围决策

| 决策 | 结论 | 理由 |
|---|---|---|
| skill-creator 形态 | **zip 包 skill 不是 system agent** | V1 已建是 skill, 用户拍板保, 不再创新 system agent |
| 评测路径 | **SubAgent fork × 2** | SKILL.md step 5 原写, V1 SubAgent infra 已有, cc 同款 |
| LLM judge | **复用 EvalJudgeTool.judgeMultiTurnConversation** | V5 已有 5 维 score, 不再造轮子 |
| baseline | **NO_SKILL** (target agent skillIds=[]) | 跟 cc with_skill vs without_skill 一致, clean baseline |
| Scenario 形态 | **EvalScenarioEntity status='ephemeral'** | 复用 V6 ephemeral pattern + EphemeralScenarioCleanupService cleanup |
| Schema 改动 | t_skill_draft 加 column, 不开新表 | 跟现 SkillDraft 流程一致 |
| 4 入口 scenario 来源 | (1)+(2) zip evals/ / (3) agent step 1 问 / (4) extract 原 session | per 入口 input 形态不同, 都转 ephemeral EvalScenarioEntity |
| Reject 处理 | status='rejected' + 不 allow force-promote | 低质 skill 直接挡 |

## 数据模型

### V91 migration (r2 fix 后 — 加 target_agent_id / candidate_skill_id / source 共 4 column)

**r1 spec review verify gap**: SkillDraftEntity 真实字段没有 `targetAgentId` / `candidateSkillId` / `source`. agentId 当前只能从 sourceSessionId → t_session.agent_id 反查; candidateSkillId 当前在 approveDraft 才物化. V91 加 3 个新 column 让评测前提显式:

```sql
-- V91__skill_draft_evaluation.sql

ALTER TABLE t_skill_draft ADD COLUMN target_agent_id BIGINT NULL;
-- 评测目标 agent (评测时 SubAgent fork 用) — extract path 从 sourceSession.agent_id 反查写入; 上传/marketplace path 操作员指定; 自然语言 path 当前 agent 自身
ALTER TABLE t_skill_draft ADD COLUMN candidate_skill_id BIGINT NULL;
-- 评测时 transient render SkillEntity 的 id (SkillCreatorService.renderToTransientSkillEntity 写入) — evaluateSkillDraft 完成后 cleanup or 接 approve flow promote 成正式 skill
ALTER TABLE t_skill_draft ADD COLUMN source VARCHAR(64) NULL;
-- 创建来源枚举: 'upload' / 'marketplace' / 'natural-language' / 'extract-from-sessions' / 'attribution' / 'manual' (跟现有 status 字段同款 free-form VARCHAR 不加 CHECK)
ALTER TABLE t_skill_draft ADD COLUMN evaluation_result_json TEXT NULL;

-- evaluation_result_json shape (Jackson serialize):
-- {
--   "with_skill": {
--     "pass_rate": 0.85,
--     "quality": 0.78,
--     "efficiency": 0.92,
--     "latency_ms": 4500,
--     "cost_usd": 0.0023
--   },
--   "without_skill": {
--     "pass_rate": 0.33,
--     "quality": 0.45,
--     "efficiency": 0.95,
--     "latency_ms": 3200,
--     "cost_usd": 0.0011
--   },
--   "delta": {
--     "pass_rate": 0.52,
--     "quality": 0.33,
--     "latency_ms": 1300,
--     "cost_usd": 0.0012
--   },
--   "llm_summary": "新加 csv-analyzer skill 显著提升 CSV 分析任务 pass_rate (+52pp), 但 latency 多 1.3s + token cost +0.12 cents. 推荐 promote.",
--   "source_session_ids": ["sess-abc", "sess-def", "sess-xyz"],
--   "scenario_count": 3,
--   "evaluated_at": "2026-05-18T10:23:45Z",
--   "evaluator_version": "skill-creator-1.0"
-- }

-- 跟现 source 字段并列加新枚举 'skill-creator-eval' (跟 'extract_from_sessions' / 'attribution' / 'manual' 同款 free-form VARCHAR 不加 CHECK 灵活)
-- 跟现 status 字段并列加新枚举 'evaluated_passed' / 'rejected' (跟 'draft' / 'approved' / 'discarded' 同款 free-form)
```

### SkillDraftEntity 加字段

```java
@Column(name = "evaluation_result_json", columnDefinition = "TEXT")
private String evaluationResultJson;

public String getEvaluationResultJson() { return evaluationResultJson; }
public void setEvaluationResultJson(String json) { this.evaluationResultJson = json; }
```

### EvaluationResult Java record (r2 fix — judge tool 真返 2 score / orchestrator 算 3 维)

**r1 spec review verify**: `EvalJudgeTool.judgeMultiTurnConversation(EvalScenario, ScenarioRunResult, MultiTurnTranscript)` 真返 `EvalJudgeMultiTurnOutput { compositeScore, overallScore, perTurnScores, attribution, rationale }`. **不返 quality/efficiency/latency/cost 5 维**. latency/cost 是 EvalOrchestrator wall-time + token 算的, judge tool 只返 score quality.

benchmark.json shape 改 (judge 出 + orchestrator metric 拼):

```java
public record EvaluationResult(
    SkillMetrics withSkill,
    SkillMetrics withoutSkill,
    SkillMetrics delta,
    String llmSummary,
    List<String> sourceSessionIds,
    int scenarioCount,
    Instant evaluatedAt,
    String evaluatorVersion
) {
    public record SkillMetrics(
        double compositeScore,    // from EvalJudgeTool.judgeMultiTurnConversation EvalJudgeMultiTurnOutput.compositeScore (0..1, judge 综合分)
        double overallScore,      // from EvalJudgeTool overall (0..1, holistic)
        double passRate,          // 算: count(compositeScore >= 0.7) / N scenarios — Service 拼
        long avgLatencyMs,        // orchestrator wall-time per scenario 平均
        double totalCostUsd       // orchestrator token cost 总
    ) {}
}
```

## 服务层 (r2 fix — SubAgent async + judge tool 真 signature + transient SkillEntity 物化时机)

### SubAgent async 收集模式 (r1 critical fix)

**r1 spec review verify**: SubAgentTool 是 **async dispatch** (CLAUDE.md "结果自动回推, 不要轮询"). `evaluateSkillDraft` 不能 sync forkAndRun + @Transactional. 改 design:

**Option A (推荐)**: 不让 evaluateSkillDraft 跨 SubAgent async 边界, 改成**两阶段**:
1. **Phase eval-dispatch** (sync, @Transactional): render transient SkillEntity 拿 `candidate_skill_id` → 写入 t_skill_draft → 创 2N ephemeral SubAgent run 行 (t_subagent_run, V1 已有) 待跑
2. **Phase eval-await** (async, listener pattern): 监听 SubAgent run 完成 event → 所有 2N child session 都到 status=completed → aggregate + judge + 写 evaluation_result_json (REQUIRES_NEW transaction)

实施 via `@TransactionalEventListener(SubAgentRunCompletedEvent, AFTER_COMMIT)` + 计数器, 跟 V6 OptimizationEventAutoTriggerListener pattern 同款.

或 **Option B (简化)**: SubAgent 现接口添 `awaitCompletion(runId, timeoutMs)` 同步 wait, evaluateSkillDraft 内串行 await 2N run. 简单但失去 async parallelism.

**本期采 Option A** — 利用 V6 已有 AFTER_COMMIT listener pattern, 跟 SkillAbCompletedEvent 同款架构.

### SubAgentTool schema 扩 (r1 verify gap)

**r1 spec review verify**: SubAgentTool 现 schema (action/agentId/agentName/task/runId/maxLoops) **无 skillIdsOverride 字段**. 需扩:

```java
// SubAgentTool.SubAgentDispatchInput 加字段:
public record SubAgentDispatchInput(
    String action,           // 现有
    Long agentId,            // 现有
    String agentName,        // 现有
    String task,             // 现有
    String runId,            // 现有
    Integer maxLoops,        // 现有
    List<Long> skillIdsOverride  // NEW: evaluateSkillDraft 传 [draftSkillId] or [] for with/without
) {}
```

SubAgentRegistry / SubAgentService dispatch 时若 `skillIdsOverride != null` → in-memory clone AgentEntity + 替换 skillIds (不写 DB).

### SkillCreatorService.evaluateSkillDraft (r2 fix)

```java
@Service
public class SkillCreatorService {

    private final SubAgentTool subAgentTool;
    private final SubAgentRegistry subAgentRegistry;       // V1 已有, 跟踪 active child run
    private final EvalJudgeTool evalJudgeTool;
    private final EvalScenarioRepository scenarioRepository;
    private final EphemeralScenarioCleanupService cleanupService;
    private final SkillDraftRepository draftRepository;
    private final SkillStorageService skillStorageService; // V6 已有 render transient SkillEntity
    private final SessionRepository sessionRepository;     // 反查 agentId from sourceSessionId
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private static final double PASS_RATE_DELTA_THRESHOLD = 0.05; // 5pp

    /**
     * Phase eval-dispatch (sync, @Transactional):
     * 1. resolve target_agent_id (from draft.targetAgentId 或 sourceSessionId 反查)
     * 2. render transient SkillEntity (复用 V6 R3 promoteDraftToTransientSkill pattern) → set draft.candidateSkillId
     * 3. dispatch 2N SubAgent run (async, skillIdsOverride 切 with/without) — runId 写入 t_subagent_run
     * 4. 返 List<SubAgentRunId> 给 caller; 真 await + judge 在 Phase eval-await
     */
    @Transactional
    public List<String> dispatchEvaluation(Long draftId, List<String> ephemeralScenarioIds) {
        SkillDraftEntity draft = draftRepository.findById(draftId).orElseThrow(...);

        // 1. resolve target_agent_id
        Long targetAgentId = draft.getTargetAgentId();
        if (targetAgentId == null && draft.getSourceSessionId() != null) {
            targetAgentId = sessionRepository.findById(draft.getSourceSessionId())
                .map(SessionEntity::getAgentId).orElseThrow(...);
            draft.setTargetAgentId(targetAgentId);
        }

        // 2. render transient SkillEntity (V6 R3 promoteDraftToTransientSkill 同款 pattern)
        SkillEntity transientSkill = renderToTransientSkillEntity(draft);
        draft.setCandidateSkillId(transientSkill.getId());
        draftRepository.save(draft);

        // 3. dispatch 2N SubAgent run
        List<String> runIds = new ArrayList<>();
        for (String scenarioId : ephemeralScenarioIds) {
            EvalScenarioEntity scenario = scenarioRepository.findById(scenarioId).orElseThrow(...);
            runIds.add(dispatchOne(targetAgentId, scenario.getTask(), List.of(transientSkill.getId()), draftId, scenarioId, "with_skill"));
            runIds.add(dispatchOne(targetAgentId, scenario.getTask(), List.of(),                       draftId, scenarioId, "without_skill"));
        }
        return runIds;
    }

    /**
     * Phase eval-await (async, AFTER_COMMIT listener — 跟 V6 OptimizationEventAutoTriggerListener 同款 pattern):
     * 1. 监听 SubAgentRunCompletedEvent
     * 2. 计数 draft 关联 runIds 完成数, 满 2N → trigger aggregate + judge + write evaluation_result
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubAgentRunCompleted(SubAgentRunCompletedEvent event) {
        // 找 draft, 看 2N runId 全 completed → aggregate + judge + write
        // judge call signature (r1 fix 后):
        //   evalJudgeTool.judgeMultiTurnConversation(scenario, scenarioRunResult, transcript)
        //   返 EvalJudgeMultiTurnOutput { compositeScore, overallScore, perTurnScores, attribution, rationale }
        // orchestrator-style metric (latency/cost) 自己从 t_subagent_run 行算 wall-time + tokenCost
        ...
        draft.setEvaluationResultJson(json);
        draft.setStatus(delta.passRate() >= PASS_RATE_DELTA_THRESHOLD ? "evaluated_passed" : "rejected");
        draftRepository.save(draft);

        cleanupService.cleanupEphemerals(scenarioIds);  // r1 fix: List<String> not List<Long>
    }

    private SkillEntity renderToTransientSkillEntity(SkillDraftEntity draft) {
        // 复用 V6 R3 SkillDraftService.promoteDraftToTransientSkill pattern:
        // - SkillStorageService.allocate path
        // - SkillCreatorService.render(draft, allocatedPath) 写磁盘 SKILL.md
        // - SkillEntity name suffix "_eval_<short-uuid>" + source="skill-creator-eval-transient" 双 pivot
        // - 评测完 cleanup or 接 approve flow promote 成正式 skill
    }

    private String dispatchOne(Long agentId, String task, List<Long> skillIds, Long draftId, String scenarioId, String baselineLabel) {
        // 用 SubAgentTool 异步派发, runId 写入 t_subagent_run
        // metadata 含 draftId / scenarioId / baselineLabel 用于 await listener match
    }
}
```

### EvalJudgeTool 真 signature (r1 fix)

```java
// 现 V5 EvalJudgeTool.java line 252 真 signature:
public EvalJudgeMultiTurnOutput judgeMultiTurnConversation(
    EvalScenario scenario,
    ScenarioRunResult runResult,
    MultiTurnTranscript transcript
)
// 返 record { compositeScore, overallScore, perTurnScores, attribution, rationale }
// onSubAgentRunCompleted 内: build MultiTurnTranscript from t_session_message (child session) + ScenarioRunResult metric
```

### EphemeralScenarioCleanupService (r1 fix 参数类型)

```java
// V6 真 signature (verify):
public void cleanupEphemerals(List<String> ephemeralIds)  // String, NOT Long (EvalScenarioEntity.id 是 String UUID)
```

### 4 入口 hook 接入 (r1 fix — signature 跟现 code 真对齐)

**r1 spec review verify**: 入口 1/2/4 真路径跟 spec 写的不一样, 重写 hook 代码:

**入口 1 (用户上传 — `SkillController.uploadSkill` → `SkillService.uploadSkill`)**:

```java
// SkillController.uploadSkill (line 254 现 endpoint @PostMapping("/upload"))
// 真路径: SkillService.uploadSkill(MultipartFile zip, Long ownerId), NOT SkillImportService.importSkill

public SkillEntity uploadSkill(MultipartFile zip, Long ownerId) {
    // 现有: 解 zip + 写磁盘 + 注册 SkillEntity
    // 新加: 解 zip 内 evals/evals.json → 转 ephemeral scenarios → 调 evaluateSkillDraft
    Path extractedPath = storageService.allocate(...);
    extractZipTo(zip, extractedPath);

    List<EvalScenarioEntity> scenarios = parseEvalsJson(extractedPath.resolve("evals/evals.json"));
    if (!scenarios.isEmpty()) {
        SkillDraftEntity draft = buildDraftFromExtracted(extractedPath, ownerId, "upload");
        draftRepository.save(draft);
        scenarioRepository.saveAll(scenarios);

        // dispatch 后 async 跑, sync return draft id 给用户; 真评测结果通过 dashboard 看
        List<String> runIds = skillCreatorService.dispatchEvaluation(draft.getId(), scenarios.stream().map(EvalScenarioEntity::getId).toList());
        return null; // dispatch only, real status update in onSubAgentRunCompleted listener
    }
    // 无 evals → 正常 attach (no eval gate)
    return registerSkillSync(extractedPath, ownerId);
}
```

**入口 2 (Marketplace 下载 — `SkillImportService.importSkill(Path, SkillSource, Long, boolean)`)**:

```java
// 真 signature: importSkill(Path sourcePath, SkillSource source, Long ownerId, boolean allowMediumRisk)
public SkillEntity importSkill(Path sourcePath, SkillSource source, Long ownerId, boolean allowMediumRisk) {
    // 现有: V1 SKILL-IMPORT 流程, 复制 sourcePath → 注册 SkillEntity + security scan
    // 新加: parse sourcePath/evals/evals.json + 类似入口 1 流程
    List<EvalScenarioEntity> scenarios = parseEvalsJson(sourcePath.resolve("evals/evals.json"));
    if (!scenarios.isEmpty()) {
        SkillDraftEntity draft = buildDraftFromMarketplace(sourcePath, ownerId, source);
        draftRepository.save(draft);
        scenarioRepository.saveAll(scenarios);

        skillCreatorService.dispatchEvaluation(draft.getId(), scenarios.stream().map(EvalScenarioEntity::getId).toList());
        return null;
    }
    return registerSkillSync(sourcePath, ownerId);
}
```

**入口 3 (自然语言描述 — skill-creator skill SubAgent path)**:

skill-creator skill 自己处理 (SKILL.md step 4-5 instruction + new `scripts/run-eval.md` SubAgent prompt template + agent runtime). 内部:
- agent step 1 顺手问用户给 2-3 test case → 写到 ephemeral EvalScenarioEntity (status='ephemeral')
- agent step 2-3 生 SKILL.md → 写 SkillDraftEntity (source='natural-language', target_agent_id=current agent)
- agent step 4-5 调 `SkillCreatorService.dispatchEvaluation(draftId, scenarioIds)`
- agent step 6 listener (onSubAgentRunCompleted) 自动跑 aggregate + judge + write evaluation_result + status

**入口 4 (Extract from sessions — `SkillDraftService.extractFromRecentSessions(Long, Long) → int`)**:

```java
// 真 signature: extractFromRecentSessions(Long agentId, Long userId) → int (count of drafts saved)
// 现有流程: 拿 N session + LLM 抽 → saveAll N draft → 返 count
public int extractFromRecentSessions(Long agentId, Long userId) {
    // 现有 (V1 PROD-LABEL-CLUSTER)
    List<SessionEntity> sourceSessions = ...;
    List<SkillDraftEntity> drafts = llm.extractPatternToDrafts(sourceSessions);
    drafts.forEach(d -> { d.setSource("extract-from-sessions"); d.setTargetAgentId(agentId); });
    drafts = draftRepository.saveAll(drafts);

    // 新加: 对每个 draft 转 ephemeral scenarios (用关联 source sessions 那批) → dispatch
    for (SkillDraftEntity draft : drafts) {
        List<EvalScenarioEntity> scenarios = sourceSessionsForDraft(draft, sourceSessions).stream()
            .map(s -> createEphemeralScenarioFromSession(s))
            .toList();
        scenarioRepository.saveAll(scenarios);
        skillCreatorService.dispatchEvaluation(draft.getId(), scenarios.stream().map(EvalScenarioEntity::getId).toList());
    }
    // async listener 自动跑 aggregate + write result + status
    return drafts.size();
}
```

## FE 改动

### SkillDraftDetailDrawer 加 evaluation report tab

```tsx
<Drawer>
    <Tabs items={[
        { key: 'overview', label: '概览', children: <SkillDraftOverview /> },
        { key: 'evaluation', label: 'Evaluation Report', children: <SkillDraftEvaluationReport result={draft.evaluationResult} /> },
        { key: 'source', label: '来源', children: <SkillDraftSource /> },
    ]} />
</Drawer>

// SkillDraftEvaluationReport.tsx 新建
<Card>
    <Statistic title="Pass rate delta" value={`+${(delta.passRate * 100).toFixed(0)}pp`} />
    <Statistic title="Latency delta" value={`+${delta.latencyMs}ms`} />
    <Table dataSource={[
        {metric: 'pass_rate', with: w.passRate, without: wo.passRate, delta: delta.passRate},
        // ...
    ]} />
    <Alert message={result.llmSummary} type={delta.passRate >= 0.05 ? 'success' : 'warning'} />
    <Button onClick={drillToSessions}>View source sessions</Button>
</Card>
```

### Rejected drafts list panel

```tsx
// pages/SkillDrafts.tsx 加 'rejected' tab
<Tabs items={[
    { key: 'pending', label: 'Pending review' },
    { key: 'evaluated_passed', label: 'Evaluated passed' },
    { key: 'rejected', label: 'Rejected' },
]} />
```

Rejected tab 显示:
- draft.name + reject reason (llm_summary) + evaluated_at
- iterate button (跳 skill-creator chat 触发 re-evaluate based on reject reason)

## 实施计划

### Phase 1.0 — 证伪 + 红测试 (~0.5 天)

- grep 现有 SubAgentTool payload shape + EvalJudgeTool.judgeMultiTurnConversation signature
- grep EphemeralScenarioCleanupService 接口
- grep SkillDraftRepository / SkillImportService 现状 (verify hook 点)
- 红测试: `SkillCreatorServiceEvaluateTest` (assert evaluate method 不存在 → compile fail)
- 红测试: V91 migration IT (assert evaluation_result_json column 不存在)

### Phase 1.1 — BE V91 + Service eval logic (~2 天)

- V91 migration: ALTER TABLE t_skill_draft ADD COLUMN evaluation_result_json TEXT
- `SkillCreatorService.evaluateSkillDraft` 真 body
- `EvaluationResult` record + `SkillMetrics` record
- `SkillEvaluationFailedException`
- BE test: SkillCreatorServiceEvaluateTest (mock SubAgentTool + EvalJudgeTool 跑 with/without 模拟 + delta 判 → status)

### Phase 1.2 — 4 入口 hook (~1.5 天)

- `SkillImportService.importSkill` 加 extractEvalsFromZip + 调 evaluate
- `SkillDraftService.extractFromRecentSessions` 加 createEphemeralScenarioFromSession + 调 evaluate
- skill-creator skill scripts/ + evals/ 文件 (SKILL.md step 4-5 prompt + benchmark schema 示例)
- BE test: 4 入口 controller test

### Phase 1.3 — FE evaluation report + Rejected list (~1.5 天)

- SkillDraftDetailDrawer evaluation tab + SkillDraftEvaluationReport component
- SkillDrafts.tsx 加 Rejected tab
- api/skillDrafts.ts 加 endpoint 拿 rejected list
- FE test: SkillDraftEvaluationReport.test.tsx + Rejected tab render

### Phase 2 — Reviewer 对抗 1 轮 + Judge (~0.5 天)

- java-reviewer (Opus, diff 估 ~1500-2000 lines 用 Opus 防 Sonnet stall) + typescript-reviewer 并行
- Judge Opus 仲裁
- mandatory fix 1 round

### Phase 1.6 — Dogfood e2e (~0.5 天)

- BE 重启 + V91 apply
- Manual e2e: 用 Main Assistant attach skill-creator skill → 自然语言 "创建 csv-analyzer skill" → 看 agent step 1 问 test case → step 4-5 SubAgent × 2 真跑 → benchmark.json 真生 → t_skill_draft.status 改对 → dashboard report panel 真显

### Phase Final (~0.5 天)

- 归档 active → archive
- delivery-index.md + todo.md + README.md 同步
- commit + push (等用户批准)

总: **~7 天 Full pipeline** (Phase 1.0-1.6 + Phase 2 review + Phase Final)

## 风险与边界

### Mid Risk

- SubAgent fork × 2 per scenario × N scenarios = 2N sub-sessions / per draft evaluation. cost 可能高 (e.g. 3 scenario = 6 SubAgent calls). 当 N>10 总 SubAgent run >20, 接 V1 SubAgentRegistry MAX_ACTIVE_CHILDREN_PER_PARENT 容量 check (现 V1 实施 verify)
- LLM judge composite_score quality 依赖 V5 EvalJudgeTool prompt, 不动. 改 prompt 是 EVAL-ASSERTIONS-EVIDENCE backlog.
- delta threshold (5pp default) 可能过严或过松, dogfood 1-2 周后调.

### r1 fix 新加 footgun (r1 spec review surface)

- **SubAgent async × @Transactional 边界**: `evaluateSkillDraft` 分两阶段 (dispatch sync / await async listener), 跟 V6 OptimizationEventAutoTriggerListener 同款 pattern. Listener 用 `@TransactionalEventListener(AFTER_COMMIT) + @Async + @Transactional(REQUIRES_NEW)` 三重注解防 Spring 6.1+ P11 教训 (CLAUDE.md flywheel-loop-closure ratify 3 已锁)
- **transient SkillEntity 物化时机**: `evaluateSkillDraft.dispatchEvaluation` 必须先 render transient SkillEntity 拿 candidate_skill_id 给 SubAgent attach. 复用 V6 R3 `SkillDraftService.promoteDraftToTransientSkill` pattern (写磁盘 + SkillEntity name "_eval_<8char>" 后缀 + source="skill-creator-eval-transient" 双 pivot). 评测完 cleanup or 接 approve flow promote
- **5 维 score 拼合来源**: EvalJudgeTool 真返 2 维 (compositeScore + overallScore), latency/cost 是 EvalOrchestrator wall-time + token. Listener aggregate 时拼合: judge 出 score + 自己从 t_subagent_run 算 latency / cost. spec EvaluationResult.SkillMetrics 改 5 字段 (compositeScore / overallScore / passRate / avgLatencyMs / totalCostUsd) 反映真拼合
- **SubAgentTool schema 扩**: SubAgent 现 schema 无 `skillIdsOverride`, 必须扩字段. 影响: SubAgentTool.java + SubAgentService.java dispatch 时 in-memory clone AgentEntity 替换 skillIds. 跟 SkillSandboxFactory.buildSandboxRegistryWithSkills (V2 已有) 同款 pattern

### Low Risk
- V91 schema 加 nullable column 不破现有 SkillDraft.
- t_skill_draft 现有 status / source 字段是 VARCHAR free-form, 加新枚举不破 schema.
- Iron Law 不动.

### 已知 follow-up
- per-assertion evidence-based grading (EVAL-ASSERTIONS-EVIDENCE backlog)
- 30 天 NO_SKILL 必要性 check cron
- Blind comparison judge
- Skill iterate workflow (operator 点 reject draft → 跳 skill-creator chat)
- delta threshold 调参 (dogfood 1-2 周后)

## Iron Law

- 核心 7+1 BE + 3 FE 文件 git diff = 0
- 0 改 SkillAbEvalService / EvalJudgeTool prompt
- 0 改 V1-V7 飞轮主路径
- 0 新表 (复用 t_skill_draft + 加 column)
- 0 新 LLM provider (复用 V5 EvalJudgeTool 的 LLM call)

## 测试计划

- BE unit: SkillCreatorServiceEvaluateTest (4-6 case: with_skill > without_skill / with_skill < without_skill → rejected / 4 路径 scenario 来源分别测 / threshold 边界)
- BE IT: V91MigrationIT (gated, 同 Phase 1 / Phase 2 同款) + SkillCreatorEvaluateWithSubAgentIT (Docker required, 实际 fork SubAgent 跑 mock scenario)
- BE 4 入口 hook test: SkillImportServiceEvaluateTest / SkillDraftServiceExtractEvaluateTest / skill-creator skill 自评 evals/evals.json 跑通
- FE: SkillDraftEvaluationReport.test.tsx (render delta + LLM summary) + RejectedSkillDrafts.test.tsx (list render + iterate button)
- e2e: BE restart + V91 apply + agent-browser goto /skills/drafts → 看 rejected list / evaluation report panel 真渲

## 评审记录

- 2026-05-18 创建 design-draft (基于 user CC-SKILL-EVAL-METHODOLOGY 调研结论 + 8 design 决策)
- ratify 8 项见 prd.md "决策记录" section
