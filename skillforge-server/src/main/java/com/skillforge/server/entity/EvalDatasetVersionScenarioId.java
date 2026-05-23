package com.skillforge.server.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * EVAL-DATASET-LAYER V1 (V110): composite primary key for
 * {@link EvalDatasetVersionScenarioEntity} → {@code (datasetVersionId, scenarioId)}.
 *
 * <p>JPA {@code @IdClass} requires this to be {@link Serializable} with
 * {@code equals}/{@code hashCode} matching the entity's two {@code @Id}
 * fields. Pattern mirrors {@link PatternSessionMemberId}.
 */
public class EvalDatasetVersionScenarioId implements Serializable {

    private String datasetVersionId;
    private String scenarioId;

    public EvalDatasetVersionScenarioId() {}

    public EvalDatasetVersionScenarioId(String datasetVersionId, String scenarioId) {
        this.datasetVersionId = datasetVersionId;
        this.scenarioId = scenarioId;
    }

    public String getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(String datasetVersionId) { this.datasetVersionId = datasetVersionId; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvalDatasetVersionScenarioId other)) return false;
        return Objects.equals(datasetVersionId, other.datasetVersionId)
                && Objects.equals(scenarioId, other.scenarioId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetVersionId, scenarioId);
    }
}
