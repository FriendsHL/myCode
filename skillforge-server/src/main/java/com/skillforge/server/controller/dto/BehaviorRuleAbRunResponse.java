package com.skillforge.server.controller.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * BEHAVIOR-RULE-AB-EVAL V1 — REST response for {@link BehaviorRuleAbRunEntity}.
 *
 * <p>Per {@code java.md} known footgun #6 / #6b: field-for-field mirror of the
 * entity plus one derived flag ({@link #dualCriteriaSatisfied}) so the FE
 * doesn't need to re-implement the gate formula. Outer shape is the single
 * record value (not an envelope) — FE wrapper is
 * {@code api.get<BehaviorRuleAbRunResponse>(...)}.
 *
 * <p>Field name + type contract preserved in
 * {@code BehaviorRuleAbRunResponseContractTest} (Jackson roundtrip).
 *
 * <p><b>FE contract location</b> (r2-BE-3): the canonical FE TypeScript
 * counterpart is {@code skillforge-dashboard/src/api/behaviorRule.ts} —
 * interface {@code BehaviorRuleAbRun}. The {@code scenarioResults} field below
 * MUST be mirrored in that base interface as
 * {@code scenarioResults?: AbScenarioResult[] | null} (not in a local
 * extension/cast subtype) so {@code tsc} verifies the cross-stack contract.
 * Per java.md footgun #6b "outer envelope shape" rule, any future field
 * addition here requires a synchronized FE base-interface edit + a paired
 * assertion in {@code BehaviorRuleAbRunResponseContractTest}.
 */
public record BehaviorRuleAbRunResponse(
        String id,
        String agentId,
        String candidateVersionId,
        String status,
        String abRunKind,
        Double baselinePassRate,
        Double candidatePassRate,
        Double deltaPassRate,
        Double targetDeltaPp,
        Double regressionDeltaPp,
        Integer targetCount,
        Integer regressionCount,
        String datasetVersionId,
        Boolean promoted,
        String failureReason,
        Instant startedAt,
        Instant completedAt,
        Boolean dualCriteriaSatisfied,
        /**
         * BEHAVIOR-RULE-AB-EVAL V1 — FE-BE contract C3 (opportunistic): per-
         * scenario baseline vs candidate scoreboard, parsed from
         * {@code BehaviorRuleAbRunEntity.abScenarioResultsJson}. {@code null}
         * when the entity column is null/blank or the JSON parse fails — FE
         * degrades gracefully (no detail table).
         */
        List<AbScenarioResult> scenarioResults) {

    private static final Logger log = LoggerFactory.getLogger(BehaviorRuleAbRunResponse.class);

    /**
     * Field-rich {@link #from} that uses the Spring-managed {@link ObjectMapper}
     * to parse the per-scenario JSON. Controllers / services that want
     * scenarioResults populated should call this overload.
     */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e, ObjectMapper objectMapper) {
        if (e == null) return null;
        List<AbScenarioResult> scenarios = parseScenarioResults(e.getAbScenarioResultsJson(), objectMapper);
        return new BehaviorRuleAbRunResponse(
                e.getId(),
                e.getAgentId(),
                e.getCandidateVersionId(),
                e.getStatus(),
                e.getAbRunKind(),
                e.getBaselinePassRate(),
                e.getCandidatePassRate(),
                e.getDeltaPassRate(),
                e.getTargetDeltaPp(),
                e.getRegressionDeltaPp(),
                e.getTargetCount(),
                e.getRegressionCount(),
                e.getDatasetVersionId(),
                e.isPromoted(),
                e.getFailureReason(),
                e.getStartedAt(),
                e.getCompletedAt(),
                // Derived: only meaningful once status=COMPLETED. For other
                // statuses we still compute the formula (returns false for
                // missing regression delta), keeping the field non-null on
                // the wire — simplifies FE rendering.
                BehaviorRulePromotionService.isDualCriteriaSatisfied(e),
                scenarios);
    }

    /**
     * Backwards-compatible {@link #from} overload — leaves scenarioResults
     * null. Used by call sites that don't want to depend on an ObjectMapper
     * (older tests / pure-projection contexts).
     */
    public static BehaviorRuleAbRunResponse from(BehaviorRuleAbRunEntity e) {
        if (e == null) return null;
        return new BehaviorRuleAbRunResponse(
                e.getId(),
                e.getAgentId(),
                e.getCandidateVersionId(),
                e.getStatus(),
                e.getAbRunKind(),
                e.getBaselinePassRate(),
                e.getCandidatePassRate(),
                e.getDeltaPassRate(),
                e.getTargetDeltaPp(),
                e.getRegressionDeltaPp(),
                e.getTargetCount(),
                e.getRegressionCount(),
                e.getDatasetVersionId(),
                e.isPromoted(),
                e.getFailureReason(),
                e.getStartedAt(),
                e.getCompletedAt(),
                BehaviorRulePromotionService.isDualCriteriaSatisfied(e),
                /*scenarioResults*/ null);
    }

    /**
     * Best-effort parse of {@code abScenarioResultsJson}. Returns null on
     * blank input or parse failure — both are non-fatal (FE degrades).
     */
    private static List<AbScenarioResult> parseScenarioResults(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || objectMapper == null) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<AbScenarioResult>>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse abScenarioResultsJson (len={}): {}",
                    json.length(), ex.getMessage());
            return null;
        }
    }
}
