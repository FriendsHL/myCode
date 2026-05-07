package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.dto.ScheduledTaskResponse;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.exception.ScheduledTaskAccessDeniedException;
import com.skillforge.server.exception.ScheduledTaskNotFoundException;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;

import java.time.Instant;
import java.util.Map;

/**
 * Shared helpers for the 5 P12 schedule-management tools.
 *
 * <p>The tools all share:
 * <ul>
 *   <li>userId resolution from {@link SkillContext} (skip the call entirely if missing
 *       — agents must run in a logged-in session for these tools)</li>
 *   <li>agentId fallback: if the agent didn't pass one, look up the current
 *       session's agent</li>
 *   <li>uniform error-result mapping for ownership / not-found / validation paths</li>
 *   <li>response serialization via the Spring-managed ObjectMapper</li>
 * </ul>
 */
final class ScheduledTaskToolSupport {

    private ScheduledTaskToolSupport() {
    }

    static Long requireUserId(SkillContext context) {
        return context != null ? context.getUserId() : null;
    }

    static SkillResult userIdMissingError() {
        return SkillResult.validationError(
                "userId not available in skill context — these tools require a logged-in session");
    }

    /**
     * Resolve the agent id the new schedule should bind to: prefer the explicit
     * input value; fall back to the current session's owning agent (so an agent
     * naturally schedules itself unless it explicitly chooses another).
     */
    static Long resolveAgentId(Object explicitAgentId, SkillContext context, SessionService sessionService) {
        Long fromInput = toLong(explicitAgentId);
        if (fromInput != null) return fromInput;
        if (context == null || context.getSessionId() == null) return null;
        try {
            return sessionService.getSession(context.getSessionId()).getAgentId();
        } catch (Exception e) {
            return null;
        }
    }

    static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static String toString(Object o) {
        return o == null ? null : o.toString();
    }

    static Boolean toBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }

    static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (NumberFormatException ignored) { return null; }
    }

    static Instant toInstant(Object o) {
        if (o == null) return null;
        return Instant.parse(o.toString());
    }

    /**
     * Coerce the input {@code channelTarget} into the wire shape consumed by
     * {@link ScheduledTaskRequest}. Accepts:
     * <ul>
     *   <li>{@code null} — leaves the field untouched on update; sets null on create</li>
     *   <li>{@code Map} — used as-is (FE shape: {@code {channelType, channelId}})</li>
     *   <li>{@code String} — parsed as JSON; if it's a JSON object, use that map</li>
     * </ul>
     * Anything else throws to surface a validation error to the caller.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> coerceChannelTarget(Object raw, ObjectMapper objectMapper) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "channelTarget string is not valid JSON: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException(
                "channelTarget must be an object {channelType, channelId} or a JSON string");
    }

    /**
     * Convert a service-layer exception into a structured tool error result.
     * Service throws {@link ScheduledTaskAccessDeniedException} on cross-user
     * access — surface it as EXECUTION error (not VALIDATION) so the agent
     * understands "permanently can't, don't retry with same args".
     */
    static SkillResult mapException(Exception e) {
        if (e instanceof ScheduledTaskAccessDeniedException ade) {
            return SkillResult.error("forbidden: " + ade.getMessage());
        }
        if (e instanceof ScheduledTaskNotFoundException nfe) {
            return SkillResult.error("not found: " + nfe.getMessage());
        }
        if (e instanceof IllegalArgumentException iae) {
            return SkillResult.validationError(iae.getMessage());
        }
        return SkillResult.error("error: " + e.getMessage());
    }

    /**
     * Serialize a task entity to JSON for the tool output. Uses the same parsed
     * {@link ScheduledTaskResponse} shape as the REST layer so agents see one
     * canonical schema.
     */
    static String serializeTask(ScheduledTaskEntity entity, ObjectMapper objectMapper) throws Exception {
        return objectMapper.writeValueAsString(ScheduledTaskResponse.from(entity, objectMapper));
    }

    /** Common-case "service does not provide this directly" guard. */
    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
