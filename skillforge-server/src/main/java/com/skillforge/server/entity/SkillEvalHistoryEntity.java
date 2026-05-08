package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * SKILL-EVOLVE-LOOP (V63): per-skill EVAL run history. One row per evaluation
 * (manual single-skill /api/skills/{id}/evaluate, or scheduled weekly run by
 * {@link com.skillforge.server.improve.SkillScheduledEvaluator}).
 *
 * <p>Self-improve loop reads {@code findFirstBySkillIdOrderByCreatedAtDesc} to
 * decide whether a skill drifted below threshold and should trigger an
 * evolve cycle (INV-4). Composite score is mandatory; the 4 dimension scores
 * are nullable to tolerate future {@code EvalScoreFormula} schema changes.
 */
@Entity
@Table(name = "t_skill_eval_history")
public class SkillEvalHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    /** Optional pointer to an existing EvalTask whose results seeded this history row. */
    @Column(name = "eval_run_id", length = 64)
    private String evalRunId;

    @Column(name = "composite_score", nullable = false)
    private Double compositeScore;

    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "efficiency_score")
    private Double efficiencyScore;

    @Column(name = "latency_score")
    private Double latencyScore;

    @Column(name = "cost_score")
    private Double costScore;

    /** {@code manual} | {@code scheduled} (DB CHECK enforced). */
    @Column(name = "triggered_by", nullable = false, length = 16)
    private String triggeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SkillEvalHistoryEntity() {
    }

    /**
     * W4 r1 fix: lifecycle-managed createdAt fallback. Use @PrePersist (not Spring Data
     * Auditing's @CreatedDate) so IT tests can deliberately set an aged createdAt to
     * exercise the 7-day skip query — Auditing would always overwrite. Keeps parity
     * with the DB DEFAULT now() in V63.
     */
    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }

    public String getEvalRunId() { return evalRunId; }
    public void setEvalRunId(String evalRunId) { this.evalRunId = evalRunId; }

    public Double getCompositeScore() { return compositeScore; }
    public void setCompositeScore(Double compositeScore) { this.compositeScore = compositeScore; }

    public Double getQualityScore() { return qualityScore; }
    public void setQualityScore(Double qualityScore) { this.qualityScore = qualityScore; }

    public Double getEfficiencyScore() { return efficiencyScore; }
    public void setEfficiencyScore(Double efficiencyScore) { this.efficiencyScore = efficiencyScore; }

    public Double getLatencyScore() { return latencyScore; }
    public void setLatencyScore(Double latencyScore) { this.latencyScore = latencyScore; }

    public Double getCostScore() { return costScore; }
    public void setCostScore(Double costScore) { this.costScore = costScore; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
