package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Map;

/**
 * EVAL-DATASET-LAYER V1 (V110): immutable snapshot of an
 * {@link EvalDatasetEntity}. Once published, the membership of scenarios in
 * the bridge table {@code t_eval_dataset_version_scenario} is frozen; the
 * service layer rejects mutations.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@link #compositionStats} — JSONB with shape
 *       {@code {benchmark:N, session_derived:M, manual:K, total, purpose_*:..., expected_baseline_pass_rate:0.xx}}.
 *       Computed at publish time; used by FE to display estimated baseline
 *       and source-type distribution.</li>
 *   <li>{@link #compositionHash} (★ r4 W1 fix ★) — SHA256 of the sorted
 *       scenario IDs. Used for cross-version diff detection and to verify
 *       a running A/B is talking about the same scenario set the version
 *       declared (race/edit safety).</li>
 *   <li>{@link #actualBaselinePassRate} (★ r4 D1 fix ★) — written by the
 *       first completed A/B run against this version (running moving
 *       average if multiple runs). FE prefers this over the static
 *       expected pass rate baked into {@code compositionStats}.</li>
 * </ul>
 */
@Entity
@Table(name = "t_eval_dataset_version")
@EntityListeners(AuditingEntityListener.class)
public class EvalDatasetVersionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "dataset_id", nullable = false, length = 36)
    private String datasetId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "composition_stats", columnDefinition = "jsonb")
    private Map<String, Object> compositionStats;

    /**
     * ★ r4 W1 fix ★ — DB column existed but Entity field was missing in r1
     * draft → silent null writes broke SHA256 diff detection. Field now
     * declared explicitly.
     */
    @Column(name = "composition_hash", length = 64)
    private String compositionHash;

    /**
     * ★ r4 D1 fix ★ — populated by {@link com.skillforge.server.improve.AbEvalPipeline}
     * after a successful A/B run completes; FE prefers this over the
     * static {@code expected_baseline_pass_rate} field in
     * {@link #compositionStats}.
     */
    @Column(name = "actual_baseline_pass_rate")
    private Double actualBaselinePassRate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    public EvalDatasetVersionEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }

    public Map<String, Object> getCompositionStats() { return compositionStats; }
    public void setCompositionStats(Map<String, Object> compositionStats) {
        this.compositionStats = compositionStats;
    }

    public String getCompositionHash() { return compositionHash; }
    public void setCompositionHash(String compositionHash) {
        this.compositionHash = compositionHash;
    }

    public Double getActualBaselinePassRate() { return actualBaselinePassRate; }
    public void setActualBaselinePassRate(Double actualBaselinePassRate) {
        this.actualBaselinePassRate = actualBaselinePassRate;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
