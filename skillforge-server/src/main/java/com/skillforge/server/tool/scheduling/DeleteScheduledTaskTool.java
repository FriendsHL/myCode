package com.skillforge.server.tool.scheduling;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.service.ScheduledTaskService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P12 agent tool: delete a scheduled task. Cascades to run history. */
public class DeleteScheduledTaskTool implements Tool {

    public static final String NAME = "DeleteScheduledTask";

    private final ScheduledTaskService scheduledTaskService;

    public DeleteScheduledTaskTool(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Delete a scheduled task and its run history (cascades). The scheduler "
                + "is informed via event so future fires are cancelled. Owner only — agents "
                + "cannot delete tasks they don't own.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_id", Map.of("type", "integer", "description", "Required: task id to delete"));
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
        try {
            scheduledTaskService.delete(taskId, userId);
            return SkillResult.success("{\"taskId\":" + taskId + ",\"status\":\"deleted\"}");
        } catch (Exception e) {
            return ScheduledTaskToolSupport.mapException(e);
        }
    }
}
