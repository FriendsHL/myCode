package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvalScenarioDraftRepository extends JpaRepository<EvalScenarioEntity, String> {

    List<EvalScenarioEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);

    List<EvalScenarioEntity> findByAgentId(String agentId);

    List<EvalScenarioEntity> findByStatus(String status);

    List<EvalScenarioEntity> findByAgentIdAndStatus(String agentId, String status);

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (2026-05-16): used by
     * {@code PromptImproverService.runAbTestAgainst} to resolve the agent's
     * canonical scenario split (default {@code "held_out"}) when the /run-ab
     * caller doesn't supply explicit {@code evalScenarioIds}. Returns empty
     * when the agent has no scenarios in the requested split — caller falls
     * back to the ephemeral path (ratify #4).
     */
    List<EvalScenarioEntity> findByAgentIdAndSplit(String agentId, String split);

    /**
     * EVAL-DATASET-LAYER V1 (V109): list scenarios by their source_type closed-
     * enum value. Used by the FE EvalScenarios source_type tab.
     */
    List<EvalScenarioEntity> findBySourceTypeOrderByCreatedAtDesc(String sourceType);

    /**
     * EVAL-DATASET-LAYER V1 (V109): list scenarios by their purpose closed-
     * enum value. Used by health / composition policy diagnostics.
     */
    List<EvalScenarioEntity> findByPurposeOrderByCreatedAtDesc(String purpose);

    // ─────────────────────────────────────────────────────────────────────
    // BEHAVIOR-RULE-AB-EVAL V1 (V114/V115): dataset / target-subset queries
    // used by BehaviorRuleAbEvalService to split a dataset version into
    // (target subset, regression subset).
    //
    // Native PostgreSQL queries — rely on JSONB `?|` (any-overlap) operator
    // that JPQL cannot express. Existing prior art (EVAL-DATASET-LAYER) is
    // PostgreSQL-only at runtime; H2 tests should mock these methods.
    // ─────────────────────────────────────────────────────────────────────

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

    /**
     * ★ r1-FIX (architect WARN): fallback when version has no
     * {@code target_trigger_tags} set — load every scenario in the dataset
     * version. Used by {@code BehaviorRuleAbEvalService} for regression-only
     * mode (target subset empty, all scenarios are regression).
     */
    @Query(value = """
            SELECT s.* FROM t_eval_scenario s
            JOIN t_eval_dataset_version_scenario b ON b.scenario_id = s.id
            WHERE b.dataset_version_id = :datasetVersionId
            """, nativeQuery = true)
    List<EvalScenarioEntity> findAllByDatasetVersionId(
            @Param("datasetVersionId") String datasetVersionId);
}
