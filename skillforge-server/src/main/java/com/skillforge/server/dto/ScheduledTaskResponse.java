package com.skillforge.server.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.ScheduledTaskEntity;

import java.time.Instant;
import java.util.Map;

/**
 * Response shape for /api/schedules. Plain record — Jackson handles serialization
 * with the Spring-managed {@code ObjectMapper} (JavaTimeModule registered).
 *
 * <p>{@code channelTarget} is returned as a parsed nested object (Map) so the FE
 * can read {@code channelType} / {@code channelId} fields directly (camelCase,
 * matching the FE {@code ChannelTarget} discriminated union). Storage layer keeps
 * a JSON-encoded TEXT column — entity → DTO conversion does the parse via
 * {@link #from(ScheduledTaskEntity, ObjectMapper)}.
 */
public record ScheduledTaskResponse(
        Long id,
        String name,
        Long creatorUserId,
        Long agentId,
        String cronExpr,
        Instant oneShotAt,
        String timezone,
        String promptTemplate,
        String sessionMode,
        String reusedSessionId,
        Object channelTarget,
        boolean enabled,
        String concurrencyPolicy,
        Instant nextFireAt,
        Instant lastFireAt,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Build a response from an entity. {@code channelTarget} is parsed from JSON
     * back to a {@code Map<String, Object>} so the FE receives the nested object
     * shape it expects ({@code {channelType, channelId}}). Bad JSON degrades to
     * {@code null} — callers expecting non-null channel push should validate at
     * write time, not at read.
     */
    public static ScheduledTaskResponse from(ScheduledTaskEntity e, ObjectMapper objectMapper) {
        Object channelTargetParsed = parseChannelTarget(e.getChannelTarget(), objectMapper);
        return new ScheduledTaskResponse(
                e.getId(),
                e.getName(),
                e.getCreatorUserId(),
                e.getAgentId(),
                e.getCronExpr(),
                e.getOneShotAt(),
                e.getTimezone(),
                e.getPromptTemplate(),
                e.getSessionMode(),
                e.getReusedSessionId(),
                channelTargetParsed,
                e.isEnabled(),
                e.getConcurrencyPolicy(),
                e.getNextFireAt(),
                e.getLastFireAt(),
                e.getStatus(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private static Object parseChannelTarget(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception ex) {
            // Defensive: malformed JSON in DB returns null rather than failing the whole list.
            return null;
        }
    }
}
