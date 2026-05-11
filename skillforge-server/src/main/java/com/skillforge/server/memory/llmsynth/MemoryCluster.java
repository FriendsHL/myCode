package com.skillforge.server.memory.llmsynth;

import com.skillforge.server.entity.MemoryEntity;

import java.util.List;
import java.util.Set;

/**
 * MEMORY-LLM-SYNTHESIS (V68): output of rule-based clustering (Phase 0).
 *
 * @param id              opaque UUID for logging
 * @param memberMemories  candidate memories in this cluster
 * @param memberIds       memory ids (used by parser to enforce sourceMemoryIds subset constraint)
 * @param dominantType    most-common business {@code type} in the cluster (info-only)
 * @param sharedTags      tags that triggered the Jaccard match (info-only)
 */
public record MemoryCluster(
        String id,
        List<MemoryEntity> memberMemories,
        Set<Long> memberIds,
        String dominantType,
        Set<String> sharedTags) {
}
