package com.skillforge.server.flywheel.run;

import java.time.Instant;

/**
 * OPT-LOOP-FRAMEWORK Sprint 4 (FR-5): JSON wire shape for a {@link FlywheelRunEntity}
 * row served by {@link FlywheelOrchestratorRunController}.
 *
 * <p>Field order is the canonical camelCase contract — FE
 * {@code FlywheelOrchestratorRunDto} TS interface must mirror it 1-to-1.
 * Timestamps serialize as ISO-8601 strings ({@code Instant.toString()}); a
 * null {@code agentId} / {@code errorReason} / {@code generatorSessionId} /
 * {@code summaryJson} / {@code windowStart} / {@code windowEnd} stays null
 * across the wire (default Jackson behaviour — no {@code @JsonInclude(NON_NULL)}
 * because the FE relies on explicit nulls to render placeholders).
 *
 * <p>Plan §3 + W1: {@code generatorSessionId} is included so the FE
 * {@code RunDetailPanel} can link to {@code /sessions/<id>}.
 */
public record FlywheelOrchestratorRunDto(
        String runId,
        String loopKind,
        String triggerSource,
        Long agentId,
        String status,
        String errorReason,
        String generatorSessionId,
        String inputJson,
        String summaryJson,
        String windowStart,
        String windowEnd,
        String createdAt,
        String updatedAt) {

    /**
     * Build a DTO from a persisted entity, stringifying {@link Instant} fields
     * as ISO-8601. The caller owns picking what subset of fields to send back
     * — this mapper is the only place that touches both shapes so the contract
     * change site is grep-able (java footgun #6 / #6b).
     */
    public static FlywheelOrchestratorRunDto from(FlywheelRunEntity r) {
        return new FlywheelOrchestratorRunDto(
                r.getId(),
                r.getLoopKind(),
                r.getTriggerSource(),
                r.getAgentId(),
                r.getStatus(),
                r.getErrorReason(),
                r.getGeneratorSessionId(),
                r.getInputJson(),
                r.getSummaryJson(),
                toIso(r.getWindowStart()),
                toIso(r.getWindowEnd()),
                toIso(r.getCreatedAt()),
                toIso(r.getUpdatedAt()));
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }
}
