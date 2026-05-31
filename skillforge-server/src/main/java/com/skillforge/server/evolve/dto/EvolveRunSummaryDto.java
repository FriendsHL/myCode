package com.skillforge.server.evolve.dto;

import java.time.Instant;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D1/FR-D4) — one item in the
 * {@code GET /api/evolve/agents/{agentId}/runs} list response envelope.
 *
 * <p>Field contract (FE will match camelCase exactly):
 * <ul>
 *   <li>{@code evolveRunId}    — the run UUID</li>
 *   <li>{@code status}         — pending / running / completed / error / paused</li>
 *   <li>{@code createdAt}      — ISO-8601 instant</li>
 *   <li>{@code updatedAt}      — ISO-8601 instant</li>
 *   <li>{@code iterationCount} — number of recorded evolve_iteration steps</li>
 *   <li>{@code finalDelta}     — last <em>kept</em> iteration's delta, or {@code null}
 *                                if no iteration has been kept yet</li>
 * </ul>
 */
public record EvolveRunSummaryDto(
        String evolveRunId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        int iterationCount,
        Double finalDelta
) {}
