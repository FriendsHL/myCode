package com.skillforge.server.flywheel.run;

import java.time.Instant;

/**
 * OPT-LOOP-FRAMEWORK Sprint 4 (FR-5): JSON wire shape for a {@link FlywheelRunStepEntity}
 * row served by {@link FlywheelOrchestratorRunController}'s detail endpoint.
 *
 * <p>Field order is the canonical camelCase contract — FE
 * {@code FlywheelOrchestratorStepDto} TS interface must mirror it 1-to-1.
 * Timestamps serialize as ISO-8601 strings.
 */
public record FlywheelOrchestratorStepDto(
        String stepRunId,
        String runId,
        String stepKind,
        String status,
        String subAgentSessionId,
        String stepInputJson,
        String stepOutputJson,
        Integer stepOutputCount,
        String errorReason,
        String createdAt,
        String updatedAt) {

    public static FlywheelOrchestratorStepDto from(FlywheelRunStepEntity s) {
        return new FlywheelOrchestratorStepDto(
                s.getId(),
                s.getRunId(),
                s.getStepKind(),
                s.getStatus(),
                s.getSubAgentSessionId(),
                s.getStepInputJson(),
                s.getStepOutputJson(),
                s.getStepOutputCount(),
                s.getErrorReason(),
                toIso(s.getCreatedAt()),
                toIso(s.getUpdatedAt()));
    }

    private static String toIso(Instant i) {
        return i == null ? null : i.toString();
    }
}
