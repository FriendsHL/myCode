package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.ScheduledTaskResponse;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.service.ScheduledTaskService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P12 agent tool: list the current user's scheduled tasks.
 *
 * <p>{@code enabled_only} filter is applied client-side here (the underlying
 * service exposes {@code findAllEnabled} but only as a global lookup; for
 * per-user filtering we list everything they own and apply the predicate).
 * That's fine at MVP scale (a single user is unlikely to have hundreds of
 * scheduled tasks).
 */
public class ListScheduledTasksTool implements Tool {

    public static final String NAME = "ListScheduledTasks";

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ScheduledTaskService scheduledTaskService;
    private final ObjectMapper objectMapper;

    public ListScheduledTasksTool(ScheduledTaskService scheduledTaskService,
                                  ObjectMapper objectMapper) {
        this.scheduledTaskService = scheduledTaskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "List the current user's scheduled tasks (newest first). Optional "
                + "enabled_only filter, limit (default 20, max 100), offset (default 0).";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("enabled_only", Map.of("type", "boolean", "description", "Filter to enabled tasks only (default false)"));
        properties.put("limit", Map.of("type", "integer", "description", "Page size, default 20, max 100"));
        properties.put("offset", Map.of("type", "integer", "description", "Pagination offset, default 0"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long userId = ScheduledTaskToolSupport.requireUserId(context);
        if (userId == null) {
            return ScheduledTaskToolSupport.userIdMissingError();
        }
        boolean enabledOnly = input != null
                && Boolean.TRUE.equals(ScheduledTaskToolSupport.toBoolean(input.get("enabled_only")));
        int limit = clampLimit(input != null ? ScheduledTaskToolSupport.toInt(input.get("limit")) : null);
        int offset = clampOffset(input != null ? ScheduledTaskToolSupport.toInt(input.get("offset")) : null);

        try {
            List<ScheduledTaskEntity> all = scheduledTaskService.listForUser(userId);
            List<ScheduledTaskEntity> filtered = enabledOnly
                    ? all.stream().filter(ScheduledTaskEntity::isEnabled).toList()
                    : all;
            int total = filtered.size();
            int from = Math.min(offset, total);
            int to = Math.min(from + limit, total);
            List<ScheduledTaskResponse> page = filtered.subList(from, to).stream()
                    .map(e -> ScheduledTaskResponse.from(e, objectMapper))
                    .toList();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", page);
            response.put("total", total);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            return ScheduledTaskToolSupport.mapException(e);
        }
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested < 1) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    private static int clampOffset(Integer requested) {
        if (requested == null || requested < 0) return 0;
        return requested;
    }
}
