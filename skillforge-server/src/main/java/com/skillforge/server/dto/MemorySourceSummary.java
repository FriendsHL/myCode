package com.skillforge.server.dto;

import com.skillforge.server.entity.MemoryEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * MEMORY-LLM-SYNTHESIS (V68) FU-1: inline source-memory preview surfaced alongside
 * {@link MemoryProposalDto#sourceMemories()}.
 *
 * <p>FE renders this so the admin can read the original memory content before
 * approving — required by PRD security acceptance #1 ("admin approve UI must show
 * source memory content so injection attempts are visible").
 *
 * @param id           source memory row id
 * @param title        source memory title
 * @param content      source memory content (raw — FE is responsible for visually escaping)
 * @param status       lifecycle status (ACTIVE / STALE / ARCHIVED) — FE highlights non-ACTIVE rows
 * @param importance   importance bucket
 * @param createdAt    when the source memory was created (Instant; null when entity has no audit data)
 * @param memoryKind   provenance (observation / reflection / optimized) — null for legacy rows
 * @param recallCount  how often this memory has been recalled
 */
public record MemorySourceSummary(
        Long id,
        String title,
        String content,
        String status,
        String importance,
        Instant createdAt,
        String memoryKind,
        int recallCount) {

    public static MemorySourceSummary from(MemoryEntity m) {
        LocalDateTime created = m.getCreatedAt();
        Instant createdInstant = created == null ? null
                : created.atZone(ZoneId.systemDefault()).toInstant();
        return new MemorySourceSummary(
                m.getId(),
                m.getTitle(),
                m.getContent(),
                m.getStatus(),
                m.getImportance(),
                createdInstant,
                m.getMemoryKind(),
                m.getRecallCount());
    }
}
