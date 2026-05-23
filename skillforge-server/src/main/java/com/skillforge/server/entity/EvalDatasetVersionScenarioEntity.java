package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * EVAL-DATASET-LAYER V1 (V110): bridge row for the n:n relationship between
 * {@link EvalDatasetVersionEntity} and {@link EvalScenarioEntity}.
 *
 * <p>Composite PK = (datasetVersionId, scenarioId) via
 * {@link EvalDatasetVersionScenarioId}. Pattern mirrors
 * {@link PatternSessionMemberEntity}.
 *
 * <p>ON DELETE semantics (defined in V110):
 * <ul>
 *   <li>{@code dataset_version_id → t_eval_dataset_version}: CASCADE — deleting
 *       a version reaps its membership rows automatically.</li>
 *   <li>{@code scenario_id → t_eval_scenario}: RESTRICT — preserves the
 *       snapshot completeness invariant (can't drop a scenario referenced by
 *       any version). After V112 seeds, every existing scenario is referenced
 *       at least once → scenarios are practically undeletable. V1 workaround:
 *       soft-delete via {@code EvalScenarioEntity.status='archived'}.</li>
 * </ul>
 */
@Entity
@Table(name = "t_eval_dataset_version_scenario")
@IdClass(EvalDatasetVersionScenarioId.class)
public class EvalDatasetVersionScenarioEntity {

    @Id
    @Column(name = "dataset_version_id", nullable = false, length = 36)
    private String datasetVersionId;

    @Id
    @Column(name = "scenario_id", nullable = false, length = 36)
    private String scenarioId;

    public EvalDatasetVersionScenarioEntity() {}

    public EvalDatasetVersionScenarioEntity(String datasetVersionId, String scenarioId) {
        this.datasetVersionId = datasetVersionId;
        this.scenarioId = scenarioId;
    }

    public String getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(String datasetVersionId) { this.datasetVersionId = datasetVersionId; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
}
