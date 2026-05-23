package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalDatasetVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * EVAL-DATASET-LAYER V1: spring-data repository for
 * {@link EvalDatasetVersionEntity}.
 */
public interface EvalDatasetVersionRepository extends JpaRepository<EvalDatasetVersionEntity, String> {

    List<EvalDatasetVersionEntity> findByDatasetIdOrderByVersionNumberDesc(String datasetId);

    Optional<EvalDatasetVersionEntity> findByDatasetIdAndVersionNumber(String datasetId, Integer versionNumber);

    /**
     * Returns the highest version_number currently in use for a dataset,
     * or empty if none exists yet. Used by
     * {@link com.skillforge.server.service.EvalDatasetService#publishVersion}
     * to compute the next version number.
     */
    @Query("SELECT MAX(v.versionNumber) FROM EvalDatasetVersionEntity v "
            + "WHERE v.datasetId = :datasetId")
    Optional<Integer> findMaxVersionNumber(@Param("datasetId") String datasetId);

    /**
     * EVAL-DATASET-LAYER V1: batch fetch of versions belonging to the given
     * dataset ids, used by {@link com.skillforge.server.controller.EvalDatasetController}
     * to enrich the list response (versionCount + latestVersion convenience
     * fields) without N+1 querying.
     */
    List<EvalDatasetVersionEntity> findByDatasetIdIn(List<String> datasetIds);
}
