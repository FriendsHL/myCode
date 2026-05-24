# BEHAVIOR-RULE-AB-EVAL — Tech Design

> 实现层 — schema / entity / service / REST / FE / 测试。
> 5 决策详见 [prd.md](prd.md)。

## §0 现状速查（侦察结论）

| 触点 | 现状 | 影响 |
|---|---|---|
| `OptimizationEventAutoTriggerListener.dispatchBehaviorRuleAutoAb` | line 187: 空 stub log "V5.1 backlog skip" | **本需求接进来** |
| `t_behavior_rule_version` (V82) | 含 status / rules_json / source_event_id / baseline_version_id | 加 `target_trigger_tags JSONB` |
| `t_behavior_rule_ab_run` (V82) | 含 baseline_version_id / candidate_version_id / status / baseline_pass_rate / candidate_pass_rate / delta_pass_rate / promoted / failure_reason | 加 `target_delta_pp / regression_delta_pp / target_count / regression_count / dataset_version_id` |
| `t_eval_scenario` (V109 改) | 含 source_type / source_ref / purpose | 加 `rule_trigger_hints JSONB` |
| `BehaviorRulePromotionService.promote` | V82 partial-UNIQUE 安全 retire→promote (适用) | 加 `promoteManual(versionId, userId)` 包装方法（不动 promote 核心）|
| `BehaviorRuleSurface.loadActive` | 5-min TTL cache，promote/rollback 后 invalidateCache | 跑 baseline 时**不动**它（baseline 走 disabledVersionIds 旁路）|
| `AbEvalPipeline.copyWithoutEvalOverrides` | r3.2 后强制 temperature=0.0 + 保留 max_loops | 继承（baseline / candidate 都走同款 copy）|
| `SystemPromptBuilder.appendBehaviorRules` (line 152) | 读 `agentDef.getBehaviorRules().getCustomRules()` + `getResolvedBehaviorRules()` 拼 system prompt | **不动**。在调用前对 AgentDefinition 做"剥/加 rule" mutation |
| `AgentService.toAgentDefinition` (line 354) | 解析 `entity.getBehaviorRules()` JSON → BehaviorRulesConfig | **不动**。下游 BehaviorRuleAbEvalService 调 toAgentDefinition 后做剥/加 |
| `BehaviorRuleVersionEntity.rulesJson` 形态 | TEXT，JSON array `[{id, priority, when, then, rationale}]` (V82 注释) | 跟 AgentEntity.behaviorRules JSON 不是同款 (后者是 BehaviorRulesConfig 含 customRules + builtinRuleIds)，需要 mapper |
| `EVAL-DATASET-LAYER V1` dataset | main-assistant-mixed-v1 (49 scenarios) | 默认 dataset 来源；本需求**只读不改**已有 dataset |

**关键洞察**：
- V4 BehaviorRuleVersionEntity.rulesJson 的 JSON 形态 (`[{id, priority, when, then, rationale}]`) 跟 AgentEntity.behaviorRules JSON (`{customRules: [{text, severity}], builtinRuleIds: [...]}`) **不同**。这意味着 candidate 注入时需要 mapper 把 V4 rules JSON 转成 AgentDefinition.BehaviorRulesConfig.CustomRule list。
- 实现选 §3.4 "BehaviorRuleVersionToCustomRulesMapper" 单一职责工具类，避免在 AbEvalPipeline 里塞 mapping 逻辑。

---

## §1 Schema（V114 / V115 / V116）

### V114__eval_scenario_add_rule_trigger_hints.sql

```sql
-- BEHAVIOR-RULE-AB-EVAL V1 (2026-05-24): give every EvalScenario an optional
-- JSONB tag list that BehaviorRuleAbEvalService consumes to split the dataset
-- into target subset (scenario hints ∩ rule target_trigger_tags ≠ ∅) and
-- regression subset (no intersection).
--
-- Nullable + default '[]' → existing 49 mixed scenarios start as "regression-
-- only" (no target hits). V116 seeds 5-15 scenarios with real hints derived
-- from task text heuristics, satisfying mrd.md AC-7 (≥5 non-empty).
--
-- JSONB chosen over TEXT (json) for two reasons:
--   1. Container @> operator allows index-supported "any overlap" queries
--      (`rule_trigger_hints ?| array['uses_bash','long_tool_output']`).
--   2. Matches V109 + V110 prior art (composition_stats / tags are JSONB).
--
-- NOT NULL with default '[]' avoids three-way ternary (null vs empty vs filled)
-- — caller code only branches on size > 0.

ALTER TABLE t_eval_scenario
    ADD COLUMN IF NOT EXISTS rule_trigger_hints JSONB NOT NULL DEFAULT '[]'::jsonb;

-- GIN index supports the `?|` (any-overlap) operator used by the target subset
-- query. Partial index (only non-empty arrays) keeps index small since the
-- vast majority of scenarios are regression-only.
CREATE INDEX IF NOT EXISTS idx_eval_scenario_rule_trigger_hints_gin
    ON t_eval_scenario USING GIN (rule_trigger_hints)
    WHERE jsonb_array_length(rule_trigger_hints) > 0;
```

### V115__behavior_rule_ab_dual_criteria.sql

```sql
-- BEHAVIOR-RULE-AB-EVAL V1 (2026-05-24): augment t_behavior_rule_ab_run with
-- dual-criteria fields + dataset linkage. Existing rows (V4 era — likely 0
-- in prod since FlywheelAutoTriggerListener.dispatchBehaviorRuleAutoAb was a
-- stub) gain nullable fields defaulting to NULL → backward compatible.
--
-- target_delta_pp / regression_delta_pp store the split-deltas; legacy
-- delta_pass_rate retained as "global delta over whole dataset" for FE
-- backwards-compat (PromptAbRunResponse-style shapes still render it). The
-- dual-criteria gate in §3.3 reads target/regression columns, not legacy.
--
-- target_trigger_tags moved to t_behavior_rule_version (not ab_run) because
-- it's a property of the rule (set when version created), not the A/B run.

ALTER TABLE t_behavior_rule_ab_run
    ADD COLUMN IF NOT EXISTS target_delta_pp     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS regression_delta_pp DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS target_count        INTEGER,
    ADD COLUMN IF NOT EXISTS regression_count    INTEGER,
    ADD COLUMN IF NOT EXISTS dataset_version_id  VARCHAR(36),
    ADD COLUMN IF NOT EXISTS candidate_eval_run_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS ab_run_kind         VARCHAR(16) NOT NULL DEFAULT 'with_vs_without';

-- ★ r1-FIX (database WARN-2): align with V111 (t_prompt_ab_run) + V113
--   (t_skill_ab_run) — both use REAL FK to t_eval_dataset_version. V115
--   was originally drafted as soft FK; matching the project convention.
--   ON DELETE RESTRICT: deleting a dataset version mid-AB-run would silently
--   strand the run pointer; force operators to retire runs first.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_brar_dataset_version') THEN
    ALTER TABLE t_behavior_rule_ab_run
        ADD CONSTRAINT fk_brar_dataset_version
        FOREIGN KEY (dataset_version_id) REFERENCES t_eval_dataset_version(id)
        ON DELETE RESTRICT;
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_brar_ab_run_kind') THEN
    ALTER TABLE t_behavior_rule_ab_run ADD CONSTRAINT chk_brar_ab_run_kind
      CHECK (ab_run_kind IN ('with_vs_without','variant_a_vs_b'));
  END IF;
END $$;

-- Soft FK to t_eval_dataset_version (no DB FK — mirrors PromptAbRun pattern).
CREATE INDEX IF NOT EXISTS idx_brar_dataset_version
    ON t_behavior_rule_ab_run(dataset_version_id)
    WHERE dataset_version_id IS NOT NULL;

-- Augment BehaviorRuleVersion with target_trigger_tags (JSONB array). Default
-- '[]' = "no targeting; runs as regression-check only" (fallback per D1).
ALTER TABLE t_behavior_rule_version
    ADD COLUMN IF NOT EXISTS target_trigger_tags JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_brv_target_trigger_tags_gin
    ON t_behavior_rule_version USING GIN (target_trigger_tags)
    WHERE jsonb_array_length(target_trigger_tags) > 0;
```

### V116__seed_rule_trigger_hints.sql

```sql
-- BEHAVIOR-RULE-AB-EVAL V1 — heuristic seed of rule_trigger_hints for the 49
-- main-assistant-mixed-v1 scenarios so target/regression split has signal
-- on day 1. Heuristic: task text keyword → hint tag. Hand-curated minimal
-- set; precision over recall (false-positive hints = wasting eval cycles
-- on regression-shaped scenarios; missing hint = scenario stays regression,
-- still informative).

-- ★ r1-FIX (database WARN-1): JSONB `||` concat + NOT @> idempotent guard
--   replace last-write-wins assignment. A scenario matching multiple ILIKE
--   patterns now accumulates ALL applicable tags (bash + multi_tool etc),
--   not just the last UPDATE's tag. Guard `NOT (... @> '["tag"]')` makes
--   each UPDATE idempotent — re-running V116 doesn't duplicate entries.

-- Token-heavy / multi-step scenarios → +"long_context"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["long_context"]'::jsonb
 WHERE source_type = 'session_derived'
   AND (task ILIKE '%research%' OR task ILIKE '%综合%'
        OR task ILIKE '%总结%'   OR task ILIKE '%汇总%')
   AND NOT (rule_trigger_hints @> '["long_context"]'::jsonb);

-- Bash / shell tool scenarios → +"uses_bash"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["uses_bash"]'::jsonb
 WHERE (task ILIKE '%bash%' OR task ILIKE '%命令%' OR task ILIKE '%shell%')
   AND NOT (rule_trigger_hints @> '["uses_bash"]'::jsonb);

-- File ops → +"uses_file_io"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["uses_file_io"]'::jsonb
 WHERE (task ILIKE '%文件%' OR task ILIKE '%file%'
        OR task ILIKE '%read%' OR task ILIKE '%write%')
   AND NOT (rule_trigger_hints @> '["uses_file_io"]'::jsonb);

-- Multi-tool sequence → +"multi_tool"
UPDATE t_eval_scenario
   SET rule_trigger_hints = rule_trigger_hints || '["multi_tool"]'::jsonb
 WHERE (task ILIKE '%step by step%' OR task ILIKE '%先%然后%' OR task ILIKE '%分步%')
   AND NOT (rule_trigger_hints @> '["multi_tool"]'::jsonb);

-- ★ r1-FIX (database WARN-3): enforce AC-7 at migration time. If fewer than
--   5 scenarios have hints after V116, fail the migration loudly so deploy
--   never silently violates acceptance. Threshold matches AC-7 floor.
DO $$
DECLARE v INTEGER;
BEGIN
    SELECT COUNT(*) INTO v FROM t_eval_scenario
        WHERE jsonb_array_length(rule_trigger_hints) > 0;
    IF v < 5 THEN
        RAISE EXCEPTION '[V116] AC-7 violation: only % scenarios have rule_trigger_hints (need >= 5). '
                        'Check V112 seed presence + ILIKE pattern coverage.', v;
    END IF;
    RAISE NOTICE '[V116] seeded rule_trigger_hints on % scenarios (AC-7 OK, threshold 5)', v;
END $$;
```

---

## §2 Entity / Repository 改动

### EvalScenarioEntity.java
```java
@Column(name = "rule_trigger_hints", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private List<String> ruleTriggerHints = new ArrayList<>();

public List<String> getRuleTriggerHints() { return ruleTriggerHints; }
public void setRuleTriggerHints(List<String> v) {
    this.ruleTriggerHints = v == null ? new ArrayList<>() : v;
}
```

### BehaviorRuleVersionEntity.java
```java
@Column(name = "target_trigger_tags", columnDefinition = "jsonb")
@JdbcTypeCode(SqlTypes.JSON)
private List<String> targetTriggerTags = new ArrayList<>();
// getter/setter 同上模式
```

### BehaviorRuleAbRunEntity.java
```java
@Column(name = "target_delta_pp")     private Double targetDeltaPp;
@Column(name = "regression_delta_pp") private Double regressionDeltaPp;
@Column(name = "target_count")        private Integer targetCount;
@Column(name = "regression_count")    private Integer regressionCount;
@Column(name = "dataset_version_id", length = 36) private String datasetVersionId;
@Column(name = "candidate_eval_run_id", length = 36) private String candidateEvalRunId;
@Column(name = "ab_run_kind", length = 16, nullable = false)
private String abRunKind = "with_vs_without";

// Constants:
public static final String KIND_WITH_VS_WITHOUT = "with_vs_without";
public static final String KIND_VARIANT_A_VS_B  = "variant_a_vs_b";  // V2 backlog
```

### EvalScenarioRepository.java
```java
// JSONB ?| array operator via native query (no JPQL portability needed —
// EVAL-DATASET-LAYER already uses PostgreSQL JSONB ops elsewhere).
@Query(value = """
    SELECT s.* FROM t_eval_scenario s
    JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
    WHERE b.dataset_version_id = :datasetVersionId
      AND s.rule_trigger_hints ?| CAST(:tags AS text[])
    """, nativeQuery = true)
List<EvalScenarioEntity> findTargetSubsetByDatasetVersionAndTags(
    @Param("datasetVersionId") String datasetVersionId,
    @Param("tags") String[] tags);

@Query(value = """
    SELECT s.* FROM t_eval_scenario s
    JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
    WHERE b.dataset_version_id = :datasetVersionId
      AND NOT (s.rule_trigger_hints ?| CAST(:tags AS text[]))
    """, nativeQuery = true)
List<EvalScenarioEntity> findRegressionSubsetByDatasetVersionAndTags(
    @Param("datasetVersionId") String datasetVersionId,
    @Param("tags") String[] tags);

// ★ r1-FIX (architect WARN): fallback when version has no target_trigger_tags
//   set — load entire dataset, used by BehaviorRuleAbEvalService for
//   regression-only mode (target subset empty, all scenarios are regression).
@Query(value = """
    SELECT s.* FROM t_eval_scenario s
    JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
    WHERE b.dataset_version_id = :datasetVersionId
    """, nativeQuery = true)
List<EvalScenarioEntity> findAllByDatasetVersionId(
    @Param("datasetVersionId") String datasetVersionId);
```

### BehaviorRuleAbRunRepository.java
```java
// Active run lookup for retry-guard (INV-6 from prd.md):
Optional<BehaviorRuleAbRunEntity> findFirstByCandidateVersionIdAndStatusIn(
    String candidateVersionId, Collection<String> statuses);
```

---

## §3 Service 层（核心）

### §3.1 BehaviorRuleVersionToCustomRulesMapper（新）

`com.skillforge.server.improve.behavior.BehaviorRuleVersionToCustomRulesMapper`

单一职责：把 V4 `rules_json` 形态 (`[{id, priority, when, then, rationale}]`) 转成 `AgentDefinition.BehaviorRulesConfig.CustomRule` list 注入 system prompt。

```java
public final class BehaviorRuleVersionToCustomRulesMapper {
    private final ObjectMapper objectMapper;
    public BehaviorRuleVersionToCustomRulesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /** rulesJson `[{id, priority, when, then, rationale}]`
     *  → CustomRule list with severity derived from priority:
     *    P0/P1 → MUST, P2 → SHOULD, P3+/unknown → MAY. */
    public List<AgentDefinition.BehaviorRulesConfig.CustomRule> toCustomRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank() || "[]".equals(rulesJson.trim())) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                rulesJson, new TypeReference<>() {});
            return rows.stream().map(this::toCustomRule).filter(Objects::nonNull).toList();
        } catch (JsonProcessingException ex) {
            log.warn("BehaviorRuleVersion rulesJson parse failed: {}", ex.getMessage());
            return List.of();
        }
    }
    private AgentDefinition.BehaviorRulesConfig.CustomRule toCustomRule(Map<String, Object> row) {
        String when = (String) row.get("when");
        String then = (String) row.get("then");
        if (then == null || then.isBlank()) return null;
        String text = when == null || when.isBlank() ? then : "When " + when + ", " + then;
        String priority = String.valueOf(row.getOrDefault("priority", "P3"));
        var severity = switch (priority.toUpperCase()) {
            case "P0", "P1" -> Severity.MUST;
            case "P2"       -> Severity.SHOULD;
            default         -> Severity.MAY;
        };
        // ★ r1-FIX (BLOCKER from architect review): AgentDefinition.CustomRule
        //   constructor is (Severity, String) NOT (String, Severity). Arg order
        //   confirmed at AgentDefinition.java:100.
        return new CustomRule(severity, text);
    }
}
```

### §3.2 BehaviorRuleAbEvalService（新 — orchestrator）

`com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService`

**为什么不复用 AbstractAbEvalRunner template**（r1-FIX, java-design WARN）：
SkillAbEvalService extends `AbstractAbEvalRunner<SkillEntity>` 的 5-hook template。本服务的 dual-criteria（D4: target subset + regression subset 两个分桶各算 delta）需要在 template 的 single-delta 抽象上做特例 hook，相当于让 template 知道"surface 可能有多 delta"——破坏 template 的 single-pass-rate 假设。V1 选择平行实现（同款生命周期但自己管 state machine），V2 等第二条 dual-criteria surface 出现时再回头抽 `AbstractDualCriteriaRunner` template。

```java
@Service
public class BehaviorRuleAbEvalService {

    // r1-FIX (architect WARN): D4 thresholds as named constants. V2 backlog —
    // make per-rule-type configurable via @ConfigurationProperties.
    // TODO(V2): BehaviorRuleAbConfig @ConfigurationProperties for per-rule
    //           or per-agent overrides (e.g. style rule may set TARGET=5).
    public static final double TARGET_DELTA_THRESHOLD_PP   = 10.0;
    public static final double REGRESSION_DELTA_FLOOR_PP   = -3.0;
    private final BehaviorRuleVersionRepository versionRepository;
    private final BehaviorRuleAbRunRepository abRunRepository;
    private final EvalScenarioRepository scenarioRepository;
    private final EvalDatasetService evalDatasetService;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final BehaviorRuleVersionToCustomRulesMapper rulesMapper;
    private final AbEvalPipeline abEvalPipeline;   // §3.5 加新 overload
    private final ChatEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final ExecutorService loopExecutor;  // @Qualifier("abEvalLoopExecutor")

    /**
     * 启 A/B：with-rule (candidate) vs without-rule (baseline).
     * 异步：返回 abRunId 立即，跑结果通过 WS broadcast。
     */
    @Transactional
    public String startAbForVersion(String candidateVersionId, String overrideDatasetVersionId) {
        BehaviorRuleVersionEntity candidate = versionRepository.findById(candidateVersionId)
            .orElseThrow(() -> new IllegalArgumentException("version not found: " + candidateVersionId));
        if (!STATUS_CANDIDATE.equals(candidate.getStatus())) {
            throw new IllegalStateException("Only candidate can start A/B: state=" + candidate.getStatus());
        }
        // INV-6: 同 version 已有 running/pending run → mark superseded 再启新的
        abRunRepository.findFirstByCandidateVersionIdAndStatusIn(
                candidateVersionId, List.of("PENDING", "RUNNING"))
            .ifPresent(r -> {
                r.setStatus("SUPERSEDED");
                r.setCompletedAt(Instant.now());
                abRunRepository.save(r);
            });

        // Dataset 解析：override > agent 默认 > 全局默认
        String datasetVersionId = overrideDatasetVersionId != null
            ? overrideDatasetVersionId
            : evalDatasetService.findDefaultVersionIdForAgent(candidate.getAgentId());
        if (datasetVersionId == null) throw new IllegalStateException(
            "No default dataset for agent: " + candidate.getAgentId());

        BehaviorRuleAbRunEntity abRun = new BehaviorRuleAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(candidate.getAgentId());
        abRun.setBaselineVersionId(/* sentinel: empty rule set */ candidate.getBaselineVersionId());
        abRun.setCandidateVersionId(candidateVersionId);
        abRun.setStatus("PENDING");
        abRun.setDatasetVersionId(datasetVersionId);
        abRun.setAbRunKind(KIND_WITH_VS_WITHOUT);
        abRunRepository.save(abRun);

        // ★ r1-FIX (java-design WARN — afterCommit race):
        //   SkillAbEvalService R4 踩过同款 race (commit 历史可查):
        //   @Transactional caller 内直接 loopExecutor.execute() 提交的线程
        //   可能在外层 TX commit 前 findById(abRunId) 找不到行 → runAsync 静默
        //   skip → abRun 永远停 PENDING。
        //   Fix: 用 TransactionSynchronizationManager 注册 afterCommit defer，
        //   保证 abRun.save() commit 后才 schedule runAsync。
        final String abRunId = abRun.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        loopExecutor.execute(() -> runAsync(abRunId));
                    }
                });
        } else {
            // 无活跃 TX (e.g. test直接调) → fall back 立即 schedule
            loopExecutor.execute(() -> runAsync(abRunId));
        }
        return abRunId;
    }

    void runAsync(String abRunId) {
        BehaviorRuleAbRunEntity abRun = abRunRepository.findById(abRunId).orElseThrow();
        try {
            abRun.setStatus("RUNNING");
            abRun.setStartedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_running");

            BehaviorRuleVersionEntity candidate = versionRepository.findById(
                abRun.getCandidateVersionId()).orElseThrow();
            AgentEntity agent = agentRepository.findById(Long.valueOf(candidate.getAgentId()))
                .orElseThrow();
            AgentDefinition baseDef = agentService.toAgentDefinition(agent);

            // Build target + regression subsets per D1
            List<String> tags = candidate.getTargetTriggerTags();
            List<EvalScenarioEntity> targetSubset, regressionSubset;
            if (tags == null || tags.isEmpty()) {
                targetSubset = List.of();   // 没指定 → 全部当 regression
                regressionSubset = scenarioRepository.findAllByDatasetVersionId(abRun.getDatasetVersionId());
            } else {
                String[] tagArr = tags.toArray(new String[0]);
                targetSubset = scenarioRepository.findTargetSubsetByDatasetVersionAndTags(
                    abRun.getDatasetVersionId(), tagArr);
                regressionSubset = scenarioRepository.findRegressionSubsetByDatasetVersionAndTags(
                    abRun.getDatasetVersionId(), tagArr);
            }
            abRun.setTargetCount(targetSubset.size());
            abRun.setRegressionCount(regressionSubset.size());

            if (targetSubset.isEmpty()) {
                log.info("[BehaviorRuleAb] target subset empty for versionId={}, "
                    + "fallback to full dataset (regression-only mode)", candidate.getId());
            }

            // 用 mapper 构造两份 AgentDefinition: baseline (剥本 rule) + candidate (带本 rule)
            AgentDefinition baselineDef = stripCandidateRule(baseDef, candidate);
            AgentDefinition candidateDef = injectCandidateRule(baseDef, candidate);

            // 跑：先 target，后 regression（顺序不重要，可并行 V2）
            List<EvalScenarioEntity> all = new ArrayList<>();
            all.addAll(targetSubset);
            all.addAll(regressionSubset);

            // ★ r1-FIX (java-design WARN — DualDefResult 抽象泄漏):
            //   AbEvalPipeline 新 overload 直接返 List<AbScenarioResult>，
            //   不再包 DualDefResult record（只有 1 字段无价值）。V2 加 token
            //   usage 等字段时再升级为 record（backward-compat）。
            List<AbScenarioResult> perScenario = abEvalPipeline.runWithExplicitDefs(
                abRun.getId(), all, baselineDef, candidateDef);

            // 算 dual-criteria delta（用 enum 替代 boolean，r1-FIX NIT）
            double targetBaseline = passRateOf(perScenario, targetSubset, Side.BASELINE);
            double targetCandidate = passRateOf(perScenario, targetSubset, Side.CANDIDATE);
            double regressionBaseline = passRateOf(perScenario, regressionSubset, Side.BASELINE);
            double regressionCandidate = passRateOf(perScenario, regressionSubset, Side.CANDIDATE);
            abRun.setTargetDeltaPp(targetSubset.isEmpty() ? null
                : targetCandidate - targetBaseline);
            abRun.setRegressionDeltaPp(regressionCandidate - regressionBaseline);
            abRun.setBaselinePassRate(
                weightedAvg(targetBaseline, targetSubset.size(),
                            regressionBaseline, regressionSubset.size()));
            abRun.setCandidatePassRate(
                weightedAvg(targetCandidate, targetSubset.size(),
                            regressionCandidate, regressionSubset.size()));
            abRun.setDeltaPassRate(abRun.getCandidatePassRate() - abRun.getBaselinePassRate());
            abRun.setAbScenarioResultsJson(objectMapper.writeValueAsString(perScenario));
            abRun.setStatus("COMPLETED");
            abRun.setCompletedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_completed");
            log.info("[BehaviorRuleAb] done abRunId={} target_delta={} regression_delta={}",
                abRun.getId(), abRun.getTargetDeltaPp(), abRun.getRegressionDeltaPp());
        } catch (Exception ex) {
            log.error("[BehaviorRuleAb] failed abRunId={}: {}", abRunId, ex.getMessage(), ex);
            abRun.setStatus("FAILED");
            abRun.setFailureReason(ex.getMessage());
            abRun.setCompletedAt(Instant.now());
            abRunRepository.save(abRun);
            broadcastStage(abRun, "ab_failed");
        }
    }

    /** r1-FIX (architect WARN): deep clone via JSON round-trip. AgentDefinition is
     *  mutable; shallow copy would share BehaviorRulesConfig reference → stripCandidate
     *  then injectCandidate on same base 会互相污染。JSON round-trip 保证两份独立。
     *  Cost: 微 (def 通常 <10KB)，安全 > 性能。 */
    private AgentDefinition cloneDef(AgentDefinition src) {
        try {
            String json = objectMapper.writeValueAsString(src);
            return objectMapper.readValue(json, AgentDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("AgentDefinition deep-clone failed", ex);
        }
    }

    /** Side enum 替代 boolean 参数（r1-FIX NIT 命名意图）。 */
    private enum Side { BASELINE, CANDIDATE }

    /** baseline: 在 base def 的 customRules 上**移除**本 candidate 对应的 rule 文本
     *  (按 candidate rules_json 里每条 rule id 做精确剔除，没匹配上的也无所谓
     *   — baseline 本就该不含本 candidate)。 */
    private AgentDefinition stripCandidateRule(AgentDefinition base, BehaviorRuleVersionEntity v) {
        AgentDefinition copy = cloneDef(base);
        // candidate 是新版 → baseline = base def 原样（base 还没装本 candidate）
        // 严格起见：保险扫一遍 customRules 不含本 candidate 文本
        if (copy.getBehaviorRules() != null) {
            List<CustomRule> candRules = rulesMapper.toCustomRules(v.getRulesJson());
            Set<String> candTexts = candRules.stream().map(CustomRule::getText)
                .collect(Collectors.toSet());
            List<CustomRule> filtered = copy.getBehaviorRules().getCustomRules() == null
                ? List.of()
                : copy.getBehaviorRules().getCustomRules().stream()
                    .filter(r -> !candTexts.contains(r.getText())).toList();
            copy.getBehaviorRules().setCustomRules(filtered);
        }
        return copy;
    }

    /** candidate: 在 base def 的 customRules 上**追加**本 candidate 对应的 rule 文本。 */
    private AgentDefinition injectCandidateRule(AgentDefinition base, BehaviorRuleVersionEntity v) {
        AgentDefinition copy = cloneDef(base);
        if (copy.getBehaviorRules() == null) {
            copy.setBehaviorRules(new BehaviorRulesConfig());
        }
        List<CustomRule> existing = copy.getBehaviorRules().getCustomRules() == null
            ? new ArrayList<>()
            : new ArrayList<>(copy.getBehaviorRules().getCustomRules());
        existing.addAll(rulesMapper.toCustomRules(v.getRulesJson()));
        copy.getBehaviorRules().setCustomRules(existing);
        return copy;
    }
}
```

### §3.3 BehaviorRulePromotionService 改造

```java
/** r1-FIX (architect WARN): PromoteResult record 定义（plan 之前漏定义）。 */
public record PromoteResult(String status, String reason) {
    public static PromoteResult noop(String reason)     { return new PromoteResult("noop", reason); }
    public static PromoteResult promoted(String versionId) { return new PromoteResult("promoted", versionId); }
}

/** 用户手动 promote 入口。校 dual-criteria 满足才放行；不动 V4 的 promote(). */
@Transactional
public PromoteResult promoteManual(String versionId, Long triggeredByUserId) {
    BehaviorRuleVersionEntity v = versionRepository.findById(versionId)
        .orElseThrow(() -> new IllegalArgumentException("version not found"));
    if (STATUS_ACTIVE.equals(v.getStatus())) {
        return PromoteResult.noop("already active");   // INV-6 幂等
    }
    if (!STATUS_CANDIDATE.equals(v.getStatus())) {
        throw new IllegalStateException("Cannot promote non-candidate: state=" + v.getStatus());
    }
    // 校 dual-criteria：取最新 COMPLETED ab_run
    BehaviorRuleAbRunEntity latestRun = abRunRepository
        .findFirstByCandidateVersionIdAndStatusOrderByCompletedAtDesc(versionId, "COMPLETED")
        .orElseThrow(() -> new IllegalStateException(
            "No completed A/B run for version " + versionId));
    if (!isDualCriteriaSatisfied(latestRun)) {
        throw new IllegalStateException(String.format(
            "Dual-criteria not satisfied: target_delta=%.2f (need >=10 or null), "
            + "regression_delta=%.2f (need >= -3)",
            latestRun.getTargetDeltaPp(), latestRun.getRegressionDeltaPp()));
    }
    promote(v);   // 复用 V4 retire→promote
    latestRun.setPromoted(true);
    latestRun.setTriggeredByUserId(triggeredByUserId);
    abRunRepository.save(latestRun);
    return PromoteResult.promoted(v.getId());
}

/** INV-5 公式: (target_delta >= TARGET_DELTA_THRESHOLD_PP OR target_delta IS NULL)
 *  AND regression_delta >= REGRESSION_DELTA_FLOOR_PP. r1-FIX 用 named constants
 *  来自 BehaviorRuleAbEvalService (§3.2 顶部) — 改阈值一处即可。 */
public static boolean isDualCriteriaSatisfied(BehaviorRuleAbRunEntity r) {
    Double t = r.getTargetDeltaPp();
    Double g = r.getRegressionDeltaPp();
    if (g == null) return false;
    if (g < BehaviorRuleAbEvalService.REGRESSION_DELTA_FLOOR_PP) return false;
    if (t == null) return true;      // fallback 模式：只看 regression
    return t >= BehaviorRuleAbEvalService.TARGET_DELTA_THRESHOLD_PP;
}
```

### §3.4 OptimizationEventAutoTriggerListener 改造

```java
// line 187 dispatchBehaviorRuleAutoAb 不再 stub
void dispatchBehaviorRuleAutoAb(OptimizationEventStageChangeEvent event) {
    String versionId = event.candidateBehaviorRuleVersionId();
    if (versionId == null) {
        log.warn("[FlywheelAutoTrigger] candidate_ready behavior_rule event lacks "
            + "candidateBehaviorRuleVersionId; skip eventId={}", event.eventId());
        return;
    }
    String abRunId = behaviorRuleAbEvalService.startAbForVersion(versionId, null);
    log.info("[FlywheelAutoTrigger] auto-triggered behavior_rule A/B: eventId={} "
        + "agentId={} candidateVersionId={} abRunId={}",
        event.eventId(), event.agentId(), versionId, abRunId);
}
```

### §3.5 AbEvalPipeline 加新 overload

```java
/** BEHAVIOR-RULE-AB-EVAL V1: explicit-defs overload. Runs every scenario
 *  twice (baseline def + candidate def) and returns merged per-scenario
 *  results. Reuses runSingleScenario + sandbox factory.
 *
 *  <b>Persistence contract</b> (r1-FIX, java-design WARN SRP):
 *  This overload is **pure compute** — it does NOT touch any t_*_ab_run row.
 *  Caller (BehaviorRuleAbEvalService) owns ALL persistence (status transitions,
 *  delta calculation, JSON serialization). Existing run() / run(abRun, ...)
 *  overloads DO persist via PromptAbRunRepository — divergent contract is
 *  intentional but cumulative SRP pressure on this class (~654 → 692 lines)
 *  means V2 should extract `AbScenarioRunner` helper (TODO comment at method top).
 *
 *  r1-FIX (java-design WARN abstraction): returns plain List<AbScenarioResult>
 *  not a DualDefResult wrapper (the single-field record had no upside). V2 may
 *  upgrade to record when token-usage fields land.
 */
public List<AbScenarioResult> runWithExplicitDefs(
        String abRunId,
        List<EvalScenarioEntity> scenarios,
        AgentDefinition baselineDef,
        AgentDefinition candidateDef) {
    // TODO(V2): extract AbScenarioRunner to relieve SRP pressure on AbEvalPipeline
    //           if a 5th eval orchestrator surface lands.
    AgentDefinition bEval = copyWithoutEvalOverrides(baselineDef);
    AgentDefinition cEval = copyWithoutEvalOverrides(candidateDef);
    // 复用 EVAL-DATASET-LAYER V1 r3.1 Semaphore cap=3 throttle
    Semaphore concurrency = new Semaphore(3);
    List<CompletableFuture<AbScenarioResult>> futures = new ArrayList<>();
    for (EvalScenarioEntity ent : scenarios) {
        EvalScenario scn = toEvalScenarioVo(ent);
        try {
            concurrency.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while throttling scenarios", ie);
        }
        futures.add(CompletableFuture.supplyAsync(() -> {
            try {
                ScenarioRunResult bRun = runSingleScenario(abRunId + ":b", scn, bEval);
                ScenarioRunResult cRun = runSingleScenario(abRunId + ":c", scn, cEval);
                var bJudge = evalJudgeTool.judge(scn, bRun);
                var cJudge = evalJudgeTool.judge(scn, cRun);
                return new AbScenarioResult(
                    scn.getId(), scn.getName(),
                    new AbScenarioResult.RunResult(bRun.getStatus(), bJudge.getCompositeScore()),
                    new AbScenarioResult.RunResult(cRun.getStatus(), cJudge.getCompositeScore()));
            } finally { concurrency.release(); }
        }, loopExecutor));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    List<AbScenarioResult> out = new ArrayList<>(futures.size());
    for (var f : futures) out.add(f.join());
    return out;
}
```

---

## §4 REST API

`BehaviorRuleVersionController.java` 加 3 endpoint:

| Method | Path | Body | 行为 |
|---|---|---|---|
| `POST` | `/api/behavior-rules/versions/{id}/run-ab` | `{ "datasetVersionId"?: string }` | 启 A/B run，返 `{ "abRunId": "..." }` |
| `POST` | `/api/behavior-rules/versions/{id}/promote` | `{}` | manual promote（校 dual-criteria），返 `{ "status": "promoted"/"noop", "reason": "..." }` |
| `GET` | `/api/behavior-rules/versions/{id}/latest-ab-run` | — | 返最新 ab_run 的 BehaviorRuleAbRunResponse（FE 用） |

**Response DTO** (`BehaviorRuleAbRunResponse`):
```java
public record BehaviorRuleAbRunResponse(
    String id, String agentId, String candidateVersionId,
    String status, String abRunKind,
    Double baselinePassRate, Double candidatePassRate, Double deltaPassRate,
    Double targetDeltaPp, Double regressionDeltaPp,
    Integer targetCount, Integer regressionCount,
    String datasetVersionId, Boolean promoted, String failureReason,
    Instant startedAt, Instant completedAt,
    Boolean dualCriteriaSatisfied   // 派生字段，前端不用算
) {}
```

**契约校验**（按 java.md footgun #6 + #6b）：
- BE Controller 全部 `return ResponseEntity.ok(<singleObj>)` —— 不是 envelope，FE `api.post<BehaviorRuleAbRunResponse>` 直接 `r.data` 取
- 字段名 grep + 真活 curl smoke 都跑（dev 验收 checklist）

---

## §5 前端改动

### §5.1 类型与 API wrapper
`src/api/behaviorRule.ts`（新）
```ts
export interface BehaviorRuleAbRun {
  id: string; agentId: string; candidateVersionId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SUPERSEDED';
  abRunKind: 'with_vs_without' | 'variant_a_vs_b';
  baselinePassRate: number | null;
  candidatePassRate: number | null;
  deltaPassRate: number | null;
  targetDeltaPp: number | null;
  regressionDeltaPp: number | null;
  targetCount: number | null;
  regressionCount: number | null;
  datasetVersionId: string | null;
  promoted: boolean | null;
  failureReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  dualCriteriaSatisfied: boolean | null;
}
export const behaviorRuleApi = {
  runAb: (versionId: string, datasetVersionId?: string) =>
    api.post<{ abRunId: string }>(`/api/behavior-rules/versions/${versionId}/run-ab`,
      { datasetVersionId }),
  promote: (versionId: string) =>
    api.post<{ status: 'promoted' | 'noop'; reason?: string }>(
      `/api/behavior-rules/versions/${versionId}/promote`, {}),
  latestAbRun: (versionId: string) =>
    api.get<BehaviorRuleAbRun>(`/api/behavior-rules/versions/${versionId}/latest-ab-run`),
};
```

### §5.2 OptimizationEvents.tsx 改造
- 跟 prompt A/B 同款渲染 behavior_rule row：
  - `state === 'candidate_ready'` 且 `surfaceType === 'behavior_rule'` 且 `latestAbRun?.status !== 'COMPLETED'` → 显 spinner "running A/B..."
  - `latestAbRun?.status === 'COMPLETED'` → 显 baseline / candidate / delta + Tag (绿色 ≥ +10pp; 黄 0~10; 红 <0)
  - `dualCriteriaSatisfied === true` → 显 `[Promote v1]` Button (primary)
  - `dualCriteriaSatisfied === false` → 显 disabled Button + Tooltip 解释原因
  - `status === 'FAILED'` 或 `candidate_ready` 长时间无 ab_run → 显 `[Retry A/B]` Button (复用 prompt retry 同款触发)

### §5.3 BehaviorRuleAbDetailDrawer.tsx（新）
- 点 row 详情打开 Drawer
- 展示 per-scenario baseline vs candidate (target subset 标黄底 / regression 标白底)
- target subset 空时显式 banner "No target scenarios matched — running in regression-check-only mode"

---

## §6 测试覆盖

| Layer | Class | 覆盖点 |
|---|---|---|
| Unit | `BehaviorRuleVersionToCustomRulesMapperTest` | rules_json 解析 (P0/P1→MUST, P2→SHOULD, P3→MAY); null/empty 返 `List.of()`; malformed JSON 不抛 (返 empty + warn) |
| Unit | `BehaviorRuleAbEvalServiceTest` (Mockito) | startAb: candidate 状态检查; 重入 (existing PENDING → SUPERSEDED); dataset resolve fallback; target subset empty → fallback log + targetDeltaPp = null |
| Unit | `BehaviorRulePromotionServiceManualPromoteTest` | dual-criteria 满足/不满足/fallback 模式 / 重复 promote (INV-6) |
| Unit | `DualCriteriaTest` | INV-5 公式 8 个边界 case (target null + regression -2 → ✓; target 9 + regression 0 → ✗; target 11 + regression -4 → ✗; ...) |
| IT | `BehaviorRuleAbEvalServiceIT` (Testcontainers PG) | end-to-end: 启 A/B → DB 落 baseline_pass_rate / candidate_pass_rate / target_delta / regression_delta；INV-1 (eval_run_ids 非 null) |
| IT | `OptimizationEventAutoTriggerListenerBehaviorRuleIT` | candidate_ready event → dispatchBehaviorRuleAutoAb 真的调 startAb (不再 skip) |
| IT | `EvalScenarioRepositoryJsonbHintIT` (Testcontainers PG) | findTargetSubset / findRegressionSubset 用 PG JSONB `?|` 正确 |
| Contract | `BehaviorRuleAbRunResponseContractTest` | ObjectMapper roundtrip 校 FE TS interface 字段名/类型一致 (per java.md #6) |
| FE Unit | `BehaviorRuleAbBadge.test.tsx` | dual-criteria Tag 颜色边界 / disabled tooltip 文案 |

---

## §7 不变量映射（prd.md INV-1 ~ INV-6）

| INV | 实现位置 |
|---|---|
| INV-1 baseline/candidate eval_run_id 非空 | `BehaviorRuleAbEvalService.runAsync` 内 V1 不创单独 eval_run（直接调 AbEvalPipeline.runWithExplicitDefs），但若 V2 接 EvalTaskEntity 时必须 INSERT `candidate_eval_run_id` 列 NOT NULL（V115 暂 nullable，V2 收紧）。**V1 阶段 INV-1 弱化为"abRun 落库时 status 必 ∈ {PENDING/RUNNING/COMPLETED/FAILED/SUPERSEDED}"** —— Postman/IT 校 |
| INV-2 baseline 真不带本 rule | `BehaviorRuleAbEvalServiceTest.baselineDefDoesNotContainCandidateRuleText` 单测：mock LlmProvider 截获 systemPrompt，assert 不含 candidate rule 内文 |
| INV-3 subset 不重不漏 | JPA query: `findTargetSubset` 用 `?|`，`findRegression` 用 `NOT (?|)` —— 集合互补；IT 校 `target.size + regression.size == fullDataset.size` |
| INV-4 fallback 模式 target_delta null | service line `setTargetDeltaPp(targetSubset.isEmpty() ? null : ...)`；UT 校 |
| INV-5 dual-criteria 公式 | `BehaviorRulePromotionService.isDualCriteriaSatisfied` static helper；`DualCriteriaTest` 8 边界 case |
| INV-6 promote 幂等 | `promoteManual` 第 4 行 `if (STATUS_ACTIVE) return noop`；UT 校 |

---

## §8 风险与回滚

| 风险 | 应对 |
|---|---|
| V114/V115/V116 在 prod dev 库重跑（Flyway version conflict） | 全部 `ADD COLUMN IF NOT EXISTS` + `DO $$ ... pg_constraint` guard；V116 UPDATE 幂等（同 WHERE 不变） |
| V4 BehaviorRuleVersion.rules_json 形态变化导致 mapper 解析失败 | mapper try-catch + log.warn 返 empty list，不抛；A/B 仍能跑（候选 rule 集合空 → 等价 baseline，delta = 0） |
| BehaviorRuleAbEvalService 与 EVAL-DATASET-LAYER V1 共享 `abEvalLoopExecutor` 撞 throttle | 共享 Semaphore cap=3 throttle（在 AbEvalPipeline.runWithExplicitDefs 内；不另起新 executor） |
| 历史 BehaviorRuleAbRunEntity 行（V4 era 可能 0 行）缺新字段 | V115 ALTER 默认 null + `ab_run_kind DEFAULT 'with_vs_without'` 兼容；FE 显示 null fallback `--`  |
| 用户在 retry 之前 Approve 别的 candidate（同 agent 多 candidate 同时跑） | t_behavior_rule_version 有 UNIQUE `uq_brv_one_active` (status='active') 但**没有** candidate 唯一约束；本服务幂等保证同 candidateVersionId 只 1 个 active run，多 candidate 并行 V1 允许（共享 Semaphore throttle 不撞 OOM）|

---

## §9 Dev 任务清单（Phase 2 拆分给 BE Dev + FE Dev）

### BE Dev 任务包
1. V114 / V115 / V116 migration SQL
2. EvalScenarioEntity / BehaviorRuleVersionEntity / BehaviorRuleAbRunEntity 字段 + getter/setter
3. EvalScenarioRepository 2 个 native query
4. BehaviorRuleAbRunRepository 2 个 finder
5. `behavior/BehaviorRuleVersionToCustomRulesMapper`（含 single-rule 单测）
6. `behavior/BehaviorRuleAbEvalService`（含 startAb + runAsync + helpers）
7. `BehaviorRulePromotionService.promoteManual` + `isDualCriteriaSatisfied` static
8. `OptimizationEventAutoTriggerListener.dispatchBehaviorRuleAutoAb` 真实现
9. `AbEvalPipeline.runWithExplicitDefs` + `DualDefResult`
10. `BehaviorRuleVersionController` 3 endpoints
11. `BehaviorRuleAbRunResponse` DTO + Mapper
12. 全部对应单测 + IT (§6)

### FE Dev 任务包
1. `src/api/behaviorRule.ts` types + wrappers
2. `OptimizationEvents.tsx` 渲染分支扩展 (behavior_rule baseline/candidate/delta + Promote/Retry buttons)
3. `BehaviorRuleAbDetailDrawer.tsx` 详情 drawer
4. WebSocket subscribe `ab_running` / `ab_completed` / `ab_failed` (behavior_rule surface)
5. 对应单测

---

## §10 与 SkillForge 不变量交叉

| 规则 | 是否触发 | 应对 |
|---|---|---|
| `persistence-shape-invariant.md` (Message JSON 字节一致) | **不触发** —— 本需求不动 ChatService / AgentLoopEngine.runInternal messages 拼装 / Message / ContentBlock | ✅ |
| `identity-column-on-rewrite.md` (t_session_message identity 列) | **不触发** —— 本需求新增列在 t_eval_scenario / t_behavior_rule_version / t_behavior_rule_ab_run，不动 t_session_message | ✅ |
| `java.md` footgun #6/#6b (FE-BE Jackson 契约) | **触发** —— 3 个新 REST endpoint + 1 个新 DTO | reviewer 显式审 outer envelope (单对象 not envelope) + 字段名/类型 + curl smoke |
| `java.md` footgun #1 (ObjectMapper JavaTimeModule) | **触发** —— Response DTO 含 Instant | 用 Spring 注入的 ObjectMapper（已注册）；mapper 单测含 Instant roundtrip |
| `verification-before-completion.md` Gate Function | **触发** —— Dev "完成" 时必跑 `mvn test` + `npm run build` + curl smoke 3 endpoint | dev 完工 message 必带证据 |
| pipeline.md 核心文件 | **不触发** —— 不动 AgentLoopEngine / Hook dispatcher / LLM provider / CompactionService / ChatService / SessionService / V1 SQL / ChatWindow / Chat / LifecycleHooksEditor。**但**触发 V4 编排关键路径 (AbEvalPipeline / OptimizationEventAutoTriggerListener / AttributionApprovalService 反向依赖) | 仍走 Full pipeline（红灯触发：跨 3+ 模块 + 新 schema） |
