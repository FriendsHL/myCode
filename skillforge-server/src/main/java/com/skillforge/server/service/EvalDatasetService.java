package com.skillforge.server.service;

import com.skillforge.server.entity.EvalDatasetEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalDatasetVersionScenarioEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.repository.EvalDatasetRepository;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.EvalDatasetVersionScenarioRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EVAL-DATASET-LAYER V1: service for the named/versioned EvalDataset surface.
 *
 * <p>SRP note (r4 java-design W3): V1 keeps CRUD / version-publishing / health
 * assessment in the same class because they're tightly coupled at this stage.
 * V2 backlog: extract {@code EvalDatasetAnalyzer} for
 * {@link #assessHealth} + {@link #computeCompositionStats}.
 */
@Service
public class EvalDatasetService {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetService.class);

    /**
     * Composition policy thresholds (V1 = warn only, never block). The numbers
     * come from the wiki research conclusion: a dataset with &lt;40% benchmark
     * scenarios tends to give 0-10% baseline pass rate, making A/B delta
     * signal noisy.
     */
    private static final double MIN_BENCHMARK_FRACTION_HEALTHY = 0.40;
    private static final double MIN_TOTAL_HEALTHY = 5.0;

    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetVersionRepository versionRepository;
    private final EvalDatasetVersionScenarioRepository bridgeRepository;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final BaselinePassRateHeuristic heuristic;

    public EvalDatasetService(EvalDatasetRepository datasetRepository,
                              EvalDatasetVersionRepository versionRepository,
                              EvalDatasetVersionScenarioRepository bridgeRepository,
                              EvalScenarioDraftRepository scenarioRepository,
                              BaselinePassRateHeuristic heuristic) {
        this.datasetRepository = datasetRepository;
        this.versionRepository = versionRepository;
        this.bridgeRepository = bridgeRepository;
        this.scenarioRepository = scenarioRepository;
        this.heuristic = heuristic;
    }

    // ─────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public EvalDatasetEntity createDataset(CreateDatasetRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("CreateDatasetRequest required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("dataset name required");
        }
        if (req.ownerId() == null) {
            throw new IllegalArgumentException("ownerId required");
        }
        EvalDatasetEntity entity = new EvalDatasetEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setOwnerId(req.ownerId());
        entity.setAgentId(req.agentId());
        entity.setTags(req.tags());
        entity.setPublic(req.isPublic());
        return datasetRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<EvalDatasetEntity> listDatasets(Long ownerId, String agentId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId required");
        }
        if (agentId != null && !agentId.isBlank()) {
            return datasetRepository.findByOwnerIdAndAgentIdOrderByCreatedAtDesc(ownerId, agentId);
        }
        return datasetRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public EvalDatasetEntity getDataset(String datasetId) {
        return datasetRepository.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset not found: " + datasetId));
    }

    @Transactional(readOnly = true)
    public List<EvalDatasetVersionEntity> listVersions(String datasetId) {
        return versionRepository.findByDatasetIdOrderByVersionNumberDesc(datasetId);
    }

    @Transactional(readOnly = true)
    public EvalDatasetVersionEntity getVersion(String datasetVersionId) {
        return versionRepository.findById(datasetVersionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dataset version not found: " + datasetVersionId));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Version publishing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Publish a new immutable version of the dataset. ★ r4 W4 fix ★: empty
     * scenarioIds list throws — the immutable-snapshot invariant requires at
     * least one scenario, and SHA256 of empty string is a collision risk
     * (every empty version would share a hash). An empty dataset is
     * representable as "dataset row with 0 versions".
     */
    @Transactional
    public EvalDatasetVersionEntity publishVersion(String datasetId,
                                                    List<String> scenarioIds,
                                                    Long userId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId required");
        }
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "publishVersion: scenarioIds is empty; an empty dataset is represented by "
                            + "a dataset row with 0 versions, not a 0-item version snapshot.");
        }
        EvalDatasetEntity dataset = getDataset(datasetId);

        // Resolve scenarios upfront — fail fast if any id is bogus, so we
        // don't leave a half-baked version row behind.
        List<EvalScenarioEntity> scenarios = scenarioRepository.findAllById(scenarioIds);
        if (scenarios.size() != scenarioIds.size()) {
            List<String> found = scenarios.stream().map(EvalScenarioEntity::getId).toList();
            List<String> missing = scenarioIds.stream()
                    .filter(id -> !found.contains(id)).toList();
            throw new IllegalArgumentException(
                    "Some scenarioIds do not exist in t_eval_scenario: " + missing);
        }

        int nextVersionNumber = versionRepository.findMaxVersionNumber(datasetId).orElse(0) + 1;

        EvalDatasetVersionEntity version = new EvalDatasetVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setDatasetId(dataset.getId());
        version.setVersionNumber(nextVersionNumber);
        version.setCompositionStats(buildCompositionStats(scenarios));
        version.setCompositionHash(computeCompositionHash(scenarioIds));
        version.setCreatedBy(userId);
        version = versionRepository.save(version);

        // Bridge rows.
        for (String sid : scenarioIds) {
            bridgeRepository.save(new EvalDatasetVersionScenarioEntity(version.getId(), sid));
        }

        log.info("[EvalDataset] published version {} v{} with {} scenarios, hash={}",
                dataset.getName(), nextVersionNumber, scenarioIds.size(),
                version.getCompositionHash());
        return version;
    }

    @Transactional(readOnly = true)
    public List<EvalScenarioEntity> getScenariosForVersion(String datasetVersionId) {
        List<String> ids = bridgeRepository.findScenarioIdsByDatasetVersionId(datasetVersionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        // Preserve the deterministic order returned by the bridge query.
        Map<String, EvalScenarioEntity> byId = scenarioRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(EvalScenarioEntity::getId, e -> e));
        List<EvalScenarioEntity> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            EvalScenarioEntity e = byId.get(id);
            if (e != null) {
                out.add(e);
            } else {
                // Defensive: an ON DELETE RESTRICT scenario row shouldn't be
                // missing under V1 invariants. Log and skip rather than
                // throwing — a partial result is still useful.
                log.warn("Bridge references missing scenario id={} (dataset_version_id={})",
                        id, datasetVersionId);
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Composition stats + health
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Build the JSONB {@code composition_stats} payload for a scenario set:
     * source_type distribution, purpose distribution, and the heuristic-
     * estimated baseline pass rate.
     */
    public Map<String, Object> buildCompositionStats(List<EvalScenarioEntity> scenarios) {
        Map<String, Integer> sourceCounts = new HashMap<>();
        Map<String, Integer> purposeCounts = new HashMap<>();
        for (EvalScenarioEntity s : scenarios) {
            sourceCounts.merge(s.getSourceType() == null ? "unknown" : s.getSourceType(), 1, Integer::sum);
            purposeCounts.merge(s.getPurpose() == null ? "unknown" : s.getPurpose(), 1, Integer::sum);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("benchmark", sourceCounts.getOrDefault(EvalScenarioEntity.SOURCE_TYPE_BENCHMARK, 0));
        stats.put("session_derived",
                sourceCounts.getOrDefault(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED, 0));
        stats.put("manual", sourceCounts.getOrDefault(EvalScenarioEntity.SOURCE_TYPE_MANUAL, 0));
        stats.put("total", scenarios.size());
        stats.put("purpose_baseline_anchor",
                purposeCounts.getOrDefault(EvalScenarioEntity.PURPOSE_BASELINE_ANCHOR, 0));
        stats.put("purpose_regression",
                purposeCounts.getOrDefault(EvalScenarioEntity.PURPOSE_REGRESSION, 0));
        stats.put("purpose_ablation",
                purposeCounts.getOrDefault(EvalScenarioEntity.PURPOSE_ABLATION, 0));
        stats.put("expected_baseline_pass_rate", round(heuristic.estimate(scenarios), 4));
        return stats;
    }

    /**
     * Convenience overload — looks up scenarios by id and delegates to
     * {@link #buildCompositionStats(List)}. Used by external callers (and
     * future REST surface) when they have only the IDs in hand.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> computeCompositionStats(List<String> scenarioIds) {
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            return buildCompositionStats(List.of());
        }
        return buildCompositionStats(scenarioRepository.findAllById(scenarioIds));
    }

    /**
     * Health assessment for a published version (UC-4 composition policy).
     * V1 = warn only, never block.
     */
    @Transactional(readOnly = true)
    public DatasetHealthAssessment assessHealth(String datasetVersionId) {
        List<EvalScenarioEntity> scenarios = getScenariosForVersion(datasetVersionId);
        List<String> warnings = new ArrayList<>();
        if (scenarios.isEmpty()) {
            warnings.add("dataset version has no scenarios — A/B run will produce no signal.");
            return new DatasetHealthAssessment(false, warnings);
        }
        int total = scenarios.size();
        long benchmarkCount = scenarios.stream()
                .filter(s -> EvalScenarioEntity.SOURCE_TYPE_BENCHMARK.equals(s.getSourceType()))
                .count();
        double benchmarkFraction = (double) benchmarkCount / total;
        if (total < MIN_TOTAL_HEALTHY) {
            warnings.add(String.format(
                    "dataset has only %d scenarios — small N makes A/B delta noisy; recommend ≥%d.",
                    total, (int) MIN_TOTAL_HEALTHY));
        }
        if (benchmarkFraction < MIN_BENCHMARK_FRACTION_HEALTHY) {
            warnings.add(String.format(
                    "dataset has %.0f%% benchmark scenarios (target ≥%.0f%%); baseline likely "
                            + "0-10%% with no candidate-improvement headroom.",
                    benchmarkFraction * 100, MIN_BENCHMARK_FRACTION_HEALTHY * 100));
        }
        return new DatasetHealthAssessment(warnings.isEmpty(), warnings);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * SHA256 of the comma-joined sorted scenario IDs. Matches the V112 seed
     * migration formula so DB-seeded versions and Java-published versions
     * use the same canonical form. ★ r4 W4 fix ★ guards against empty list
     * before reaching this method.
     */
    static String computeCompositionHash(List<String> scenarioIds) {
        List<String> sorted = new ArrayList<>(scenarioIds);
        sorted.sort(String::compareTo);
        String csv = String.join(",", sorted);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(csv.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JDK 8+ — this is structurally impossible.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static double round(double v, int places) {
        double scale = Math.pow(10, places);
        return Math.round(v * scale) / scale;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Request / response types
    // ─────────────────────────────────────────────────────────────────────

    /** Request payload for {@link #createDataset(CreateDatasetRequest)}. */
    public record CreateDatasetRequest(String name,
                                        String description,
                                        Long ownerId,
                                        String agentId,
                                        List<String> tags,
                                        boolean isPublic) {}

    /**
     * Outcome of {@link #assessHealth(String)} — V1 only warns, never blocks.
     * {@code warnings} is empty when {@code isHealthy} is true.
     */
    public record DatasetHealthAssessment(boolean isHealthy, List<String> warnings) {}

    /**
     * V1 r2 UX fix (2026-05-24): pick a sensible default dataset version for an
     * attribution-triggered A/B run that didn't specify one explicitly.
     *
     * <p>Preference order (first non-empty wins):
     * <ol>
     *   <li>Agent-specific mixed dataset (name pattern contains "mixed"),
     *       {@code agent_id = agentId}</li>
     *   <li>Global mixed dataset ({@code agent_id IS NULL} + name contains "mixed")</li>
     *   <li>Agent-specific baseline dataset (name pattern contains "baseline")</li>
     *   <li>Global baseline dataset</li>
     * </ol>
     *
     * <p>Returns the latest version_id of the chosen dataset, or {@code null}
     * if no candidate found (caller falls back to legacy ephemeral path).
     *
     * <p>Why "mixed" preferred over "baseline": mixed contains benchmark
     * (baseline_anchor purpose, gives ≥30% baseline pass rate) + session_derived
     * (regression purpose, catches "candidate broke historical-failure case"),
     * so it's the best default for attribution-derived A/B.
     */
    @Transactional(readOnly = true)
    public String findDefaultVersionIdForAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;

        // Preference 1+2: any dataset matching agent (specific or global) with "mixed" in name
        String mixedId = pickLatestVersionByPattern(agentId, "mixed");
        if (mixedId != null) return mixedId;

        // Preference 3+4: fallback to "baseline" pattern
        String baselineId = pickLatestVersionByPattern(agentId, "baseline");
        if (baselineId != null) return baselineId;

        log.debug("findDefaultVersionIdForAgent: no default dataset matched for agentId={}, returning null",
                agentId);
        return null;
    }

    private String pickLatestVersionByPattern(String agentId, String namePattern) {
        // Prefer agent-specific; fallback to global (agent_id NULL).
        List<EvalDatasetEntity> candidates = datasetRepository.findAll().stream()
                .filter(d -> d.getName() != null
                        && d.getName().toLowerCase().contains(namePattern.toLowerCase()))
                .filter(d -> agentId.equals(d.getAgentId()) || d.getAgentId() == null)
                .sorted((a, b) -> {
                    // Agent-specific wins over global
                    boolean aSpecific = agentId.equals(a.getAgentId());
                    boolean bSpecific = agentId.equals(b.getAgentId());
                    if (aSpecific != bSpecific) return aSpecific ? -1 : 1;
                    return 0;
                })
                .toList();
        for (EvalDatasetEntity d : candidates) {
            Integer maxVersion = versionRepository.findMaxVersionNumber(d.getId()).orElse(null);
            if (maxVersion == null) continue;
            String versionId = versionRepository
                    .findByDatasetIdAndVersionNumber(d.getId(), maxVersion)
                    .map(EvalDatasetVersionEntity::getId)
                    .orElse(null);
            if (versionId != null) return versionId;
        }
        return null;
    }
}
