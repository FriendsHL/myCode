package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalDatasetVersionScenarioEntity;
import com.skillforge.server.entity.EvalDatasetVersionScenarioId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * EVAL-DATASET-LAYER V1: spring-data repository for
 * {@link EvalDatasetVersionScenarioEntity} (n:n bridge).
 */
public interface EvalDatasetVersionScenarioRepository
        extends JpaRepository<EvalDatasetVersionScenarioEntity, EvalDatasetVersionScenarioId> {

    List<EvalDatasetVersionScenarioEntity> findByDatasetVersionId(String datasetVersionId);

    List<EvalDatasetVersionScenarioEntity> findByScenarioId(String scenarioId);

    /**
     * Returns just the scenario_ids attached to a dataset version, ordered
     * deterministically (alphabetical) so callers can hash without re-sorting.
     */
    @Query("SELECT b.scenarioId FROM EvalDatasetVersionScenarioEntity b "
            + "WHERE b.datasetVersionId = :datasetVersionId ORDER BY b.scenarioId")
    List<String> findScenarioIdsByDatasetVersionId(@Param("datasetVersionId") String datasetVersionId);

    /**
     * Returns the dataset_version_ids that reference a given scenario. Used
     * by health/usage UIs ("which datasets is this scenario in").
     */
    @Query("SELECT b.datasetVersionId FROM EvalDatasetVersionScenarioEntity b "
            + "WHERE b.scenarioId = :scenarioId")
    List<String> findDatasetVersionIdsByScenarioId(@Param("scenarioId") String scenarioId);
}
