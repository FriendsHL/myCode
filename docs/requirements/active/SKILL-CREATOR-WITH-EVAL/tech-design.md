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

### V91 migration

```sql
-- V91__skill_draft_evaluation_result.sql

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

### EvaluationResult Java record (Jackson 序列化)

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
        double passRate,
        double quality,
        double efficiency,
        long latencyMs,
        double costUsd
    ) {}
}
```

## 服务层

### SkillCreatorService.evaluateSkillDraft

```java
@Service
public class SkillCreatorService {

    private final SubAgentTool subAgentTool;
    private final EvalJudgeTool evalJudgeTool;
    private final EvalScenarioRepository scenarioRepository;
    private final EphemeralScenarioCleanupService cleanupService;
    private final SkillDraftRepository draftRepository;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;

    private static final double PASS_RATE_DELTA_THRESHOLD = 0.05; // 5pp

    /**
     * Evaluate a SkillDraft via SubAgent fork × 2 (with_skill / without_skill).
     *
     * @param draftId target draft ID
     * @param scenarios ephemeral EvalScenarioEntity list (already persisted with status='ephemeral')
     * @return EvaluationResult containing benchmark + LLM summary
     */
    @Transactional
    public EvaluationResult evaluateSkillDraft(Long draftId, List<EvalScenarioEntity> scenarios) {
        SkillDraftEntity draft = draftRepository.findById(draftId)
            .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        Long targetAgentId = draft.getTargetAgentId();
        // skillId for "with_skill" path — draft 已 rendered to SkillEntity (transient)
        Long draftSkillId = draft.getCandidateSkillId();

        List<TranscriptResult> withResults = new ArrayList<>();
        List<TranscriptResult> withoutResults = new ArrayList<>();

        try {
            for (EvalScenarioEntity scenario : scenarios) {
                // Fork SubAgent × 2 per scenario
                String withTranscript = forkAndRun(targetAgentId, scenario, List.of(draftSkillId), "with_skill");
                String withoutTranscript = forkAndRun(targetAgentId, scenario, List.of(), "without_skill");

                // Judge each transcript
                JudgeResult withJudge = evalJudgeTool.judgeMultiTurnConversation(withTranscript, scenario);
                JudgeResult withoutJudge = evalJudgeTool.judgeMultiTurnConversation(withoutTranscript, scenario);

                withResults.add(new TranscriptResult(scenario.getId(), withTranscript, withJudge));
                withoutResults.add(new TranscriptResult(scenario.getId(), withoutTranscript, withoutJudge));
            }

            // Aggregate benchmark
            SkillMetrics withSkill = aggregate(withResults);
            SkillMetrics withoutSkill = aggregate(withoutResults);
            SkillMetrics delta = computeDelta(withSkill, withoutSkill);

            // LLM summary
            String summary = generateSummary(draft, withSkill, withoutSkill, delta);

            EvaluationResult result = new EvaluationResult(
                withSkill, withoutSkill, delta, summary,
                scenarios.stream().map(EvalScenarioEntity::getSourceSessionId).toList(),
                scenarios.size(),
                Instant.now(),
                "skill-creator-1.0"
            );

            // Persist + status update
            draft.setEvaluationResultJson(objectMapper.writeValueAsString(result));
            draft.setStatus(delta.passRate() >= PASS_RATE_DELTA_THRESHOLD ? "evaluated_passed" : "rejected");
            draftRepository.save(draft);

            return result;
        } finally {
            // V6 ephemeral cleanup
            cleanupService.cleanupEphemerals(scenarios.stream().map(EvalScenarioEntity::getId).toList());
        }
    }

    private String forkAndRun(Long agentId, EvalScenarioEntity scenario, List<Long> skillIdsOverride, String baseline) {
        // Use SubAgentTool to fork sub-session with clean context
        // Returns transcript string
    }

    private SkillMetrics aggregate(List<TranscriptResult> results) { ... }
    private SkillMetrics computeDelta(SkillMetrics w, SkillMetrics wo) { ... }
    private String generateSummary(SkillDraftEntity draft, SkillMetrics w, SkillMetrics wo, SkillMetrics delta) {
        // LLM call: 给 draft.skillMd + 2 path benchmark → 让 LLM 总结 "这 skill 加/减 value 原因"
    }
}
```

### 4 入口 hook 接入

**入口 1+2 (SkillImportService.importSkill)**:

```java
public SkillEntity importSkill(MultipartFile zip, Long userId, String sourceType) {
    // 现有: 解 zip + 写磁盘 + 注册 SkillEntity
    // 新加: 提取 zip 内 evals/evals.json → 转 ephemeral scenarios
    List<EvalScenarioEntity> scenarios = extractEvalsFromZip(zip);
    if (!scenarios.isEmpty()) {
        SkillDraftEntity draft = createDraftFromImport(zip, sourceType);
        EvaluationResult result = skillCreatorService.evaluateSkillDraft(draft.getId(), scenarios);
        if (result.delta().passRate() < PASS_RATE_DELTA_THRESHOLD) {
            // status='rejected' 不 attach 给 agent
            throw new SkillEvaluationFailedException(result.llmSummary());
        }
    }
    // 通过 (无 evals 或 delta 够) → 正常 attach
    return registerSkill(zip);
}
```

**入口 3 (skill-creator skill SubAgent path)**:

skill-creator skill 自己处理 (SKILL.md step 4-5 instruction + scripts/ + agent runtime). 内部调:
- `SkillDraftService.createDraftFromNaturalLanguage(userPrompt, agentId)` → SkillDraftEntity
- `SkillCreatorService.evaluateSkillDraft(draftId, scenariosFromAgentDialog)` (agent 通过 SubAgentTool 跟 user 对话 step 1 收 test cases)

**入口 4 (SkillDraftService.extractFromRecentSessions)**:

```java
public SkillDraftEntity extractFromRecentSessions(Long agentId) {
    // 现有: 拿 N session + LLM 抽 pattern → 生 SkillDraft
    SkillDraftEntity draft = ...;
    List<SessionEntity> sourceSessions = ...;

    // 新加: 转 ephemeral scenarios → evaluate
    List<EvalScenarioEntity> scenarios = sourceSessions.stream()
        .map(s -> createEphemeralScenarioFromSession(s))
        .toList();
    scenarioRepository.saveAll(scenarios);

    EvaluationResult result = skillCreatorService.evaluateSkillDraft(draft.getId(), scenarios);
    // draft.status 已被 evaluate 改: 'evaluated_passed' / 'rejected'
    return draft;
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
- SubAgent fork × 2 per scenario × N scenarios = 2N sub-sessions / per draft evaluation. cost 可能高 (e.g. 3 scenario = 6 SubAgent calls).
- LLM judge composite_score quality 依赖 V5 EvalJudgeTool prompt, 不动. 改 prompt 是 EVAL-ASSERTIONS-EVIDENCE backlog.
- delta threshold (5pp default) 可能过严或过松, dogfood 1-2 周后调.

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
