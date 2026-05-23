package com.skillforge.server.controller;

import com.skillforge.server.entity.EvalDatasetEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.EvalDatasetVersionScenarioRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVAL-DATASET-LAYER V1: REST surface for the named/versioned EvalDataset
 * concept introduced in V110.
 *
 * <p>Response shape conventions (java.md known footgun #6 / #6b) — all
 * endpoints below return bare objects / arrays at the top level, NOT an
 * envelope. The FE TypeScript interfaces should mirror these one-to-one:
 *
 * <ul>
 *   <li>{@code POST /api/eval/datasets} → bare {@link EvalDatasetEntity}-shaped Map</li>
 *   <li>{@code GET /api/eval/datasets} → bare {@code List<EvalDatasetEntity>}-shaped array</li>
 *   <li>{@code GET /api/eval/datasets/{id}} → bare {@link EvalDatasetEntity}-shaped Map</li>
 *   <li>{@code POST /api/eval/datasets/{id}/versions} → bare {@link EvalDatasetVersionEntity}-shaped Map</li>
 *   <li>{@code GET /api/eval/datasets/{id}/versions} → bare array</li>
 *   <li>{@code GET /api/eval/dataset-versions/{id}} → envelope
 *       {@code {version: {...}, scenarioIds: [...], scenarios: [...]}}</li>
 *   <li>{@code GET /api/eval/dataset-versions/{id}/health} → bare
 *       {@code {isHealthy: boolean, warnings: string[]}}</li>
 * </ul>
 *
 * <p>Note the single envelope endpoint (`/dataset-versions/{id}`) — the FE
 * needs to render the version row and its scenarios together; merging avoids
 * a round-trip. Other endpoints stay bare to keep the FE wrapper simple
 * (`r.data ?? []` for lists, `r.data` for singletons).
 */
@RestController
@RequestMapping("/api/eval")
public class EvalDatasetController {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetController.class);

    private final EvalDatasetService evalDatasetService;
    private final EvalDatasetVersionRepository versionRepository;
    private final EvalDatasetVersionScenarioRepository bridgeRepository;

    public EvalDatasetController(EvalDatasetService evalDatasetService,
                                  EvalDatasetVersionRepository versionRepository,
                                  EvalDatasetVersionScenarioRepository bridgeRepository) {
        this.evalDatasetService = evalDatasetService;
        this.versionRepository = versionRepository;
        this.bridgeRepository = bridgeRepository;
    }

    /**
     * Create a new named dataset. Returns the freshly-created dataset row
     * (bare Map, not enveloped). The created dataset has 0 versions; call
     * {@code POST /{id}/versions} to publish the first snapshot.
     */
    @PostMapping("/datasets")
    public ResponseEntity<Map<String, Object>> createDataset(@RequestBody CreateDatasetBody body) {
        try {
            EvalDatasetService.CreateDatasetRequest req =
                    new EvalDatasetService.CreateDatasetRequest(
                            body.name(), body.description(), body.ownerId(),
                            body.agentId(), body.tags(),
                            Boolean.TRUE.equals(body.isPublic()));
            EvalDatasetEntity created = evalDatasetService.createDataset(req);
            return ResponseEntity.ok(toDatasetMap(created));
        } catch (IllegalArgumentException e) {
            log.warn("createDataset 400: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List datasets owned by {@code ownerId} (required), optionally filtered
     * by {@code agentId}. Returns a bare array.
     */
    @GetMapping("/datasets")
    public ResponseEntity<List<Map<String, Object>>> listDatasets(
            @RequestParam("ownerId") Long ownerId,
            @RequestParam(value = "agentId", required = false) String agentId) {
        if (ownerId == null) {
            return ResponseEntity.badRequest().body(List.of(Map.of("error", "ownerId required")));
        }
        List<EvalDatasetEntity> datasets = evalDatasetService.listDatasets(ownerId, agentId);
        if (datasets.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        // EVAL-DATASET-LAYER V1: batch-enrich each dataset with versionCount /
        // latestVersionNumber / latestScenarioCount / latestExpectedBaselinePassRate /
        // latestActualBaselinePassRate so the FE list view can render without
        // N+1 round-trips. Per dataset, "latest" = highest version_number.
        List<String> datasetIds = datasets.stream().map(EvalDatasetEntity::getId).toList();
        List<EvalDatasetVersionEntity> allVersions = versionRepository.findByDatasetIdIn(datasetIds);
        // Pre-index: datasetId → list of versions; pick max by version_number.
        java.util.Map<String, EvalDatasetVersionEntity> latestByDatasetId = new java.util.HashMap<>();
        java.util.Map<String, Integer> countByDatasetId = new java.util.HashMap<>();
        for (EvalDatasetVersionEntity v : allVersions) {
            countByDatasetId.merge(v.getDatasetId(), 1, Integer::sum);
            EvalDatasetVersionEntity prior = latestByDatasetId.get(v.getDatasetId());
            if (prior == null || v.getVersionNumber() > prior.getVersionNumber()) {
                latestByDatasetId.put(v.getDatasetId(), v);
            }
        }
        // Resolve scenario counts for the latest versions in one batch.
        java.util.Map<String, Integer> scenarioCountByVersionId = new java.util.HashMap<>();
        for (EvalDatasetVersionEntity latest : latestByDatasetId.values()) {
            scenarioCountByVersionId.put(latest.getId(),
                    bridgeRepository.findScenarioIdsByDatasetVersionId(latest.getId()).size());
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(datasets.size());
        for (EvalDatasetEntity d : datasets) {
            Map<String, Object> map = toDatasetMap(d);
            int vCount = countByDatasetId.getOrDefault(d.getId(), 0);
            map.put("versionCount", vCount);
            EvalDatasetVersionEntity latest = latestByDatasetId.get(d.getId());
            if (latest != null) {
                map.put("latestVersionNumber", latest.getVersionNumber());
                map.put("latestVersionId", latest.getId());
                map.put("latestScenarioCount",
                        scenarioCountByVersionId.getOrDefault(latest.getId(), 0));
                map.put("latestActualBaselinePassRate", latest.getActualBaselinePassRate());
                // expected_baseline_pass_rate is nested in composition_stats (snake_case
                // key per V112 + buildCompositionStats convention).
                Object expected = latest.getCompositionStats() == null ? null
                        : latest.getCompositionStats().get("expected_baseline_pass_rate");
                map.put("latestExpectedBaselinePassRate", expected);
            } else {
                map.put("latestVersionNumber", null);
                map.put("latestVersionId", null);
                map.put("latestScenarioCount", 0);
                map.put("latestActualBaselinePassRate", null);
                map.put("latestExpectedBaselinePassRate", null);
            }
            out.add(map);
        }
        return ResponseEntity.ok(out);
    }

    /** Get a single dataset by id. */
    @GetMapping("/datasets/{id}")
    public ResponseEntity<Map<String, Object>> getDataset(@PathVariable("id") String id) {
        try {
            EvalDatasetEntity dataset = evalDatasetService.getDataset(id);
            return ResponseEntity.ok(toDatasetMap(dataset));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Publish a new immutable version of a dataset.
     * Body: {@code {scenarioIds: [...], userId: ...}}.
     */
    @PostMapping("/datasets/{id}/versions")
    public ResponseEntity<Map<String, Object>> publishVersion(@PathVariable("id") String id,
                                                               @RequestBody PublishVersionBody body) {
        try {
            EvalDatasetVersionEntity version =
                    evalDatasetService.publishVersion(id, body.scenarioIds(), body.userId());
            return ResponseEntity.ok(toVersionMap(version));
        } catch (IllegalArgumentException e) {
            log.warn("publishVersion 400: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** List versions of a dataset, newest first. */
    @GetMapping("/datasets/{id}/versions")
    public ResponseEntity<List<Map<String, Object>>> listVersions(@PathVariable("id") String id) {
        List<EvalDatasetVersionEntity> versions = evalDatasetService.listVersions(id);
        return ResponseEntity.ok(versions.stream().map(this::toVersionMap).toList());
    }

    /**
     * Get a single dataset version + its scenarios. Returns the envelope
     * {@code {version: {...}, scenarioIds: [...], scenarios: [...]}}.
     */
    @GetMapping("/dataset-versions/{id}")
    public ResponseEntity<Map<String, Object>> getVersionWithScenarios(@PathVariable("id") String id) {
        try {
            EvalDatasetVersionEntity version = evalDatasetService.getVersion(id);
            List<EvalScenarioEntity> scenarios = evalDatasetService.getScenariosForVersion(id);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("version", toVersionMap(version));
            envelope.put("scenarioIds", scenarios.stream().map(EvalScenarioEntity::getId).toList());
            envelope.put("scenarios", scenarios.stream().map(this::toScenarioBriefMap).toList());
            return ResponseEntity.ok(envelope);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Composition health (UC-4): warnings list + isHealthy flag. */
    @GetMapping("/dataset-versions/{id}/health")
    public ResponseEntity<Map<String, Object>> assessHealth(@PathVariable("id") String id) {
        EvalDatasetService.DatasetHealthAssessment assessment =
                evalDatasetService.assessHealth(id);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("isHealthy", assessment.isHealthy());
        map.put("warnings", assessment.warnings());
        return ResponseEntity.ok(map);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO body records
    // ─────────────────────────────────────────────────────────────────────

    /** Request body for {@link #createDataset(CreateDatasetBody)}. */
    public record CreateDatasetBody(String name,
                                     String description,
                                     Long ownerId,
                                     String agentId,
                                     List<String> tags,
                                     Boolean isPublic) {}

    /** Request body for {@link #publishVersion(String, PublishVersionBody)}. */
    public record PublishVersionBody(List<String> scenarioIds, Long userId) {}

    // ─────────────────────────────────────────────────────────────────────
    // Map serialisation helpers (explicit so FE TS interface contracts stay
    // pinned — java.md known footgun #6).
    // ─────────────────────────────────────────────────────────────────────

    private Map<String, Object> toDatasetMap(EvalDatasetEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("ownerId", entity.getOwnerId());
        map.put("agentId", entity.getAgentId());
        map.put("tags", entity.getTags());
        map.put("isPublic", entity.isPublic());
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toVersionMap(EvalDatasetVersionEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("datasetId", entity.getDatasetId());
        map.put("versionNumber", entity.getVersionNumber());
        map.put("compositionStats", entity.getCompositionStats());
        map.put("compositionHash", entity.getCompositionHash());
        map.put("actualBaselinePassRate", entity.getActualBaselinePassRate());
        map.put("createdAt", entity.getCreatedAt());
        map.put("createdBy", entity.getCreatedBy());
        return map;
    }

    /** Lightweight scenario projection used in the version detail envelope. */
    private Map<String, Object> toScenarioBriefMap(EvalScenarioEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("name", entity.getName());
        map.put("agentId", entity.getAgentId());
        map.put("sourceType", entity.getSourceType());
        map.put("sourceRef", entity.getSourceRef());
        map.put("purpose", entity.getPurpose());
        map.put("oracleType", entity.getOracleType());
        map.put("status", entity.getStatus());
        return map;
    }
}
