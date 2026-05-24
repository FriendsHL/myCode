package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1: A/B run row for the behavior_rule
 * surface. Mirrors {@link PromptAbRunEntity} so the V4
 * {@code AbstractAbEvalRunner<V>} (Phase 1.2) can drive all three surfaces
 * via the same Template Method.
 *
 * <p>Backed by V82 {@code t_behavior_rule_ab_run}. Phase 1.1 only persists +
 * round-trips rows (verified by {@code BehaviorRulePersistenceIT}); the real
 * A/B pipeline wiring lands in Phase 1.2 once the abstract runner exists.
 */
@Entity
@Table(name = "t_behavior_rule_ab_run")
public class BehaviorRuleAbRunEntity {

    public static final String STATUS_PENDING    = "PENDING";
    public static final String STATUS_RUNNING    = "RUNNING";
    public static final String STATUS_COMPLETED  = "COMPLETED";
    public static final String STATUS_FAILED     = "FAILED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** BEHAVIOR-RULE-AB-EVAL V1: A/B kind discriminator (DB CHECK chk_brar_ab_run_kind). */
    public static final String KIND_WITH_VS_WITHOUT = "with_vs_without";
    public static final String KIND_VARIANT_A_VS_B  = "variant_a_vs_b";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "baseline_version_id", length = 36, nullable = false)
    private String baselineVersionId;

    @Column(name = "candidate_version_id", length = 36, nullable = false)
    private String candidateVersionId;

    @Column(name = "baseline_eval_run_id", length = 36)
    private String baselineEvalRunId;

    @Column(length = 32, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "baseline_pass_rate")
    private Double baselinePassRate;

    @Column(name = "candidate_pass_rate")
    private Double candidatePassRate;

    @Column(name = "delta_pass_rate")
    private Double deltaPassRate;

    @Column(nullable = false)
    private boolean promoted = false;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "ab_scenario_results_json", columnDefinition = "TEXT")
    private String abScenarioResultsJson;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // BEHAVIOR-RULE-AB-EVAL V1 (V115): dual-criteria + dataset linkage.

    @Column(name = "target_delta_pp")
    private Double targetDeltaPp;

    @Column(name = "regression_delta_pp")
    private Double regressionDeltaPp;

    @Column(name = "target_count")
    private Integer targetCount;

    @Column(name = "regression_count")
    private Integer regressionCount;

    @Column(name = "dataset_version_id", length = 36)
    private String datasetVersionId;

    @Column(name = "candidate_eval_run_id", length = 36)
    private String candidateEvalRunId;

    @Column(name = "ab_run_kind", length = 16, nullable = false)
    private String abRunKind = KIND_WITH_VS_WITHOUT;

    public BehaviorRuleAbRunEntity() {}

    @PrePersist
    void onPrePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getBaselineVersionId() { return baselineVersionId; }
    public void setBaselineVersionId(String baselineVersionId) { this.baselineVersionId = baselineVersionId; }

    public String getCandidateVersionId() { return candidateVersionId; }
    public void setCandidateVersionId(String candidateVersionId) { this.candidateVersionId = candidateVersionId; }

    public String getBaselineEvalRunId() { return baselineEvalRunId; }
    public void setBaselineEvalRunId(String baselineEvalRunId) { this.baselineEvalRunId = baselineEvalRunId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getBaselinePassRate() { return baselinePassRate; }
    public void setBaselinePassRate(Double baselinePassRate) { this.baselinePassRate = baselinePassRate; }

    public Double getCandidatePassRate() { return candidatePassRate; }
    public void setCandidatePassRate(Double candidatePassRate) { this.candidatePassRate = candidatePassRate; }

    public Double getDeltaPassRate() { return deltaPassRate; }
    public void setDeltaPassRate(Double deltaPassRate) { this.deltaPassRate = deltaPassRate; }

    public boolean isPromoted() { return promoted; }
    public void setPromoted(boolean promoted) { this.promoted = promoted; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getAbScenarioResultsJson() { return abScenarioResultsJson; }
    public void setAbScenarioResultsJson(String abScenarioResultsJson) { this.abScenarioResultsJson = abScenarioResultsJson; }

    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    // BEHAVIOR-RULE-AB-EVAL V1 getters/setters.

    public Double getTargetDeltaPp() { return targetDeltaPp; }
    public void setTargetDeltaPp(Double targetDeltaPp) { this.targetDeltaPp = targetDeltaPp; }

    public Double getRegressionDeltaPp() { return regressionDeltaPp; }
    public void setRegressionDeltaPp(Double regressionDeltaPp) {
        this.regressionDeltaPp = regressionDeltaPp;
    }

    public Integer getTargetCount() { return targetCount; }
    public void setTargetCount(Integer targetCount) { this.targetCount = targetCount; }

    public Integer getRegressionCount() { return regressionCount; }
    public void setRegressionCount(Integer regressionCount) {
        this.regressionCount = regressionCount;
    }

    public String getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(String datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public String getCandidateEvalRunId() { return candidateEvalRunId; }
    public void setCandidateEvalRunId(String candidateEvalRunId) {
        this.candidateEvalRunId = candidateEvalRunId;
    }

    public String getAbRunKind() { return abRunKind; }
    public void setAbRunKind(String abRunKind) {
        this.abRunKind = abRunKind == null ? KIND_WITH_VS_WITHOUT : abRunKind;
    }
}
