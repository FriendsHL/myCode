package com.skillforge.server.tool.memorysynth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEMORY-LLM-SYNTHESIS dogfood (D22): list user IDs that had a real user message in the
 * last N days. Master {@code memory-curator} agent calls this to fan out into per-user
 * sub-sessions via {@code SubAgent}.
 *
 * <p>Implementation reuses {@link SessionRepository#findDistinctUserIdsWithRecentUserMessage}
 * already in service for {@code MemoryConsolidationScheduler}. Read-only.
 */
public class ListActiveUsersTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListActiveUsersTool.class);

    private static final int DEFAULT_LOOKBACK_DAYS = 7;
    private static final int MIN_LOOKBACK_DAYS = 1;
    private static final int MAX_LOOKBACK_DAYS = 90;

    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public ListActiveUsersTool(SessionRepository sessionRepository, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ListActiveUsers";
    }

    @Override
    public String getDescription() {
        return "List user IDs that had at least one real user message in the last N days "
                + "(default " + DEFAULT_LOOKBACK_DAYS + ", clamped to [" + MIN_LOOKBACK_DAYS
                + ", " + MAX_LOOKBACK_DAYS + "]). Used by the memory-curator master agent to "
                + "fan out into per-user sub-sessions for memory consolidation. Read-only.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("lookbackDays", Map.of(
                "type", "integer",
                "description", "Look back this many days for user activity. Default "
                        + DEFAULT_LOOKBACK_DAYS + ", clamped to [" + MIN_LOOKBACK_DAYS
                        + ", " + MAX_LOOKBACK_DAYS + "]."
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            int lookbackDays = SkillInputUtils.toInt(
                    input != null ? input.get("lookbackDays") : null, DEFAULT_LOOKBACK_DAYS);
            if (lookbackDays < MIN_LOOKBACK_DAYS) lookbackDays = MIN_LOOKBACK_DAYS;
            if (lookbackDays > MAX_LOOKBACK_DAYS) lookbackDays = MAX_LOOKBACK_DAYS;

            Instant since = Instant.now().minus(Duration.ofDays(lookbackDays));
            List<Long> userIds = sessionRepository.findDistinctUserIdsWithRecentUserMessage(since);
            // Defensive copy + drop nulls (JPA distinct may include nulls in pathological data).
            // Gap-1 fix: also drop SYSTEM_USER_ID=0 — SYSTEM-authored sessions (e.g.
            // memory-curator master/sub) are not "active users" eligible for memory
            // synthesis; downstream ListMemoryCandidates rejects userId=0 with
            // "must be positive", which would waste a SubAgent slot at the master.
            List<Long> safe = userIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(id -> id > 0L)
                    .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userIds", safe);
            payload.put("count", safe.size());
            payload.put("lookbackDays", lookbackDays);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("ListActiveUsersTool execute failed", e);
            return SkillResult.error("ListActiveUsers error: " + e.getMessage());
        }
    }
}
