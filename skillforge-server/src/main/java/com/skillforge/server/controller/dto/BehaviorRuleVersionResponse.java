package com.skillforge.server.controller.dto;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;

import java.time.Instant;

/**
 * V4 Phase 1.4 — REST response DTO for {@link BehaviorRuleVersionEntity}.
 *
 * <p>Field-for-field mirror of the JPA entity (per {@code java.md} known
 * footgun #6: BE/FE contract drift is caught only by roundtrip — keep DTO ⇄
 * entity 1:1 so that {@code grep} can verify both sides match).
 *
 * <p>Used by both list ({@code GET /api/behavior-rules/versions?agentId=&status=})
 * and detail ({@code GET /api/behavior-rules/versions/{id}}) endpoints. The
 * dashboard's behavior_rule canary panel parses this shape directly into its
 * TS interface.
 */
public record BehaviorRuleVersionResponse(
        String id,
        String agentId,
        int versionNumber,
        String status,
        String source,
        String rulesJson,
        String improvementRationale,
        Long sourceEventId,
        String baselineVersionId,
        Instant createdAt,
        Instant promotedAt) {

    public static BehaviorRuleVersionResponse from(BehaviorRuleVersionEntity e) {
        return new BehaviorRuleVersionResponse(
                e.getId(),
                e.getAgentId(),
                e.getVersionNumber(),
                e.getStatus(),
                e.getSource(),
                e.getRulesJson(),
                e.getImprovementRationale(),
                e.getSourceEventId(),
                e.getBaselineVersionId(),
                e.getCreatedAt(),
                e.getPromotedAt());
    }
}
