package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.memory.llmsynth.MemoryCluster;
import com.skillforge.server.memory.llmsynth.MemoryClusterer;
import com.skillforge.server.repository.MemoryRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEMORY-LLM-SYNTHESIS dogfood (Tool 3): cluster a list of memory IDs into topic groups
 * before the curator agent emits proposals. Delegates to the canonical
 * {@link MemoryClusterer} so the algorithm (tag-Jaccard ≥ 0.3 OR 7d window for untagged,
 * union-find, min size 3, max size 15, max 10 clusters) stays identical to the legacy
 * non-dogfood synthesizer path.
 *
 * <p>Thresholds in the input schema are advisory only — the underlying clusterer uses
 * fixed canonical constants ({@link MemoryClusterer#JACCARD_THRESHOLD},
 * {@link MemoryClusterer#WINDOW_DAYS}, {@link MemoryClusterer#MAX_CLUSTER_SIZE}). They are
 * exposed in the schema so the LLM understands the contract; values other than the
 * defaults are accepted but ignored (logged at debug). Read-only.
 */
public class ClusterMemoriesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ClusterMemoriesTool.class);

    private static final int MAX_INPUT_IDS = 200;

    private final MemoryClusterer memoryClusterer;
    private final MemoryRepository memoryRepository;
    private final ObjectMapper objectMapper;

    public ClusterMemoriesTool(MemoryClusterer memoryClusterer,
                                MemoryRepository memoryRepository,
                                ObjectMapper objectMapper) {
        this.memoryClusterer = memoryClusterer;
        this.memoryRepository = memoryRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ClusterMemories";
    }

    @Override
    public String getDescription() {
        return "Cluster a list of memory IDs by tag overlap (Jaccard >= 0.3) and a 7-day "
                + "update window (no-tag fallback). Returns clusters of size >= 3, capped at "
                + "15 members per cluster and 10 clusters total. Use this between "
                + "ListMemoryCandidates and CreateMemoryProposal so each proposal references "
                + "only memories that actually belong together. Read-only.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("memoryIds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer"),
                "description", "Required. Memory IDs to cluster (at most " + MAX_INPUT_IDS
                        + "). IDs not found in the database are silently dropped."
        ));
        properties.put("jaccardThreshold", Map.of(
                "type", "number",
                "description", "Advisory. Fixed at "
                        + MemoryClusterer.JACCARD_THRESHOLD + " internally; provided for future tuning."
        ));
        properties.put("windowDays", Map.of(
                "type", "integer",
                "description", "Advisory. Fixed at " + MemoryClusterer.WINDOW_DAYS
                        + " internally; provided for future tuning."
        ));
        properties.put("maxClusterSize", Map.of(
                "type", "integer",
                "description", "Advisory. Fixed at " + MemoryClusterer.MAX_CLUSTER_SIZE
                        + " internally; provided for future tuning."
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("memoryIds"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            List<Long> memoryIds = parseLongList(input.get("memoryIds"));
            if (memoryIds.isEmpty()) {
                return SkillResult.validationError("memoryIds is required and must be a non-empty array of integers");
            }
            if (memoryIds.size() > MAX_INPUT_IDS) {
                return SkillResult.validationError("memoryIds size " + memoryIds.size()
                        + " exceeds max " + MAX_INPUT_IDS);
            }

            // Log advisory params for observability; values are not propagated.
            if (input.get("jaccardThreshold") != null || input.get("windowDays") != null
                    || input.get("maxClusterSize") != null) {
                log.debug("ClusterMemoriesTool: advisory threshold params present but ignored "
                                + "(canonical clusterer constants in use): jaccardThreshold={} windowDays={} maxClusterSize={}",
                        input.get("jaccardThreshold"), input.get("windowDays"), input.get("maxClusterSize"));
            }

            List<MemoryEntity> memories = memoryRepository.findAllById(memoryIds);
            if (memories.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("clusters", List.of());
                empty.put("clusterCount", 0);
                empty.put("inputCount", memoryIds.size());
                empty.put("resolvedCount", 0);
                return SkillResult.success(objectMapper.writeValueAsString(empty));
            }

            List<MemoryCluster> clusters = memoryClusterer.cluster(memories);

            List<Map<String, Object>> dtos = new ArrayList<>(clusters.size());
            for (MemoryCluster c : clusters) {
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("clusterId", c.id());
                dto.put("memberIds", new ArrayList<>(c.memberIds()));
                dto.put("dominantType", c.dominantType());
                dto.put("sharedTags", new ArrayList<>(c.sharedTags()));
                dto.put("size", c.memberMemories().size());
                dtos.add(dto);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("clusters", dtos);
            payload.put("clusterCount", dtos.size());
            payload.put("inputCount", memoryIds.size());
            payload.put("resolvedCount", memories.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("ClusterMemoriesTool execute failed", e);
            return SkillResult.error("ClusterMemories error: " + e.getMessage());
        }
    }

    private static List<Long> parseLongList(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        List<Long> out = new ArrayList<>();
        for (Object item : iterable) {
            Long v = SkillInputUtils.toLong(item);
            if (v != null && v > 0) {
                out.add(v);
            }
        }
        return out;
    }
}
