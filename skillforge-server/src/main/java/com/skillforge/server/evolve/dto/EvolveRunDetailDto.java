package com.skillforge.server.evolve.dto;

import java.time.Instant;
import java.util.List;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D3) — full detail for the
 * {@code GET /api/evolve/runs/{evolveRunId}} endpoint.
 *
 * <p>Returned as a single JSON object (NOT enveloped). FE TS interface
 * must match camelCase field names exactly (footgun #6b):
 * <pre>
 * {
 *   "evolveRunId":  "...",
 *   "agentId":      7,
 *   "agentName":    "my-agent",
 *   "status":       "completed",
 *   "createdAt":    "2026-05-31T10:00:00Z",
 *   "updatedAt":    "2026-05-31T12:30:00Z",
 *   "iterations":   [ { "iteration": 1, ... }, ... ]
 * }
 * </pre>
 */
public record EvolveRunDetailDto(
        String evolveRunId,
        Long agentId,
        String agentName,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<EvolveIterationDto> iterations
) {}
