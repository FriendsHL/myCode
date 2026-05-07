package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.ScheduledTaskResponse;
import com.skillforge.server.dto.ScheduledTaskRunResponse;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.entity.ScheduledTaskRunEntity;
import com.skillforge.server.service.ScheduledTaskService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P12 agent tool: get a single scheduled task by id; optionally include recent run history. */
public class GetScheduledTaskTool implements Tool {

    public static final String NAME = "GetScheduledTask";

    private static final int RECENT_RUN_LIMIT = 10;

    private final ScheduledTaskService scheduledTaskService;
    private final ObjectMapper objectMapper;

    public GetScheduledTaskTool(ScheduledTaskService scheduledTaskService,
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
        return "Get a scheduled task by id. Set include_recent_runs=true to also "
                + "embed up to the 10 most recent run rows. Owner only.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_id", Map.of("type", "integer", "description", "Required: task id"));
        properties.put("include_recent_runs", Map.of(
                "type", "boolean",
                "description", "Embed up to 10 most-recent run rows (default false)"
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("task_id"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long userId = ScheduledTaskToolSupport.requireUserId(context);
        if (userId == null) {
            return ScheduledTaskToolSupport.userIdMissingError();
        }
        Long taskId = ScheduledTaskToolSupport.toLong(input != null ? input.get("task_id") : null);
        if (taskId == null) {
            return SkillResult.validationError("task_id is required");
        }
        boolean includeRuns = input != null
                && Boolean.TRUE.equals(ScheduledTaskToolSupport.toBoolean(input.get("include_recent_runs")));
        try {
            ScheduledTaskEntity task = scheduledTaskService.get(taskId, userId);
            ScheduledTaskResponse taskDto = ScheduledTaskResponse.from(task, objectMapper);
            if (!includeRuns) {
                return SkillResult.success(objectMapper.writeValueAsString(taskDto));
            }
            List<ScheduledTaskRunEntity> runs = scheduledTaskService.listRuns(
                    taskId, userId, RECENT_RUN_LIMIT, 0);
            List<ScheduledTaskRunResponse> runDtos = runs.stream()
                    .map(ScheduledTaskRunResponse::from).toList();
            Map<String, Object> combined = objectMapper.convertValue(taskDto, Map.class);
            combined.put("recent_runs", runDtos);
            return SkillResult.success(objectMapper.writeValueAsString(combined));
        } catch (Exception e) {
            return ScheduledTaskToolSupport.mapException(e);
        }
    }
}
