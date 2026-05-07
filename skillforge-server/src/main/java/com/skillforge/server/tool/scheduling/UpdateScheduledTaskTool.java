package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.service.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P12 agent tool: update an existing scheduled task. Supports partial patches —
 * any field omitted from input is left unchanged. Cron ↔ one-shot conversion
 * (INV-3) requires sending both the new field set AND the old field as
 * {@code null} explicitly (the request body parser distinguishes "absent" from
 * "present-as-null").
 */
public class UpdateScheduledTaskTool implements Tool {

    public static final String NAME = "UpdateScheduledTask";

    private static final Logger log = LoggerFactory.getLogger(UpdateScheduledTaskTool.class);

    private final ScheduledTaskService scheduledTaskService;
    private final ObjectMapper objectMapper;

    public UpdateScheduledTaskTool(ScheduledTaskService scheduledTaskService,
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
        return "Update an existing scheduled task. Required: task_id. All other fields "
                + "are partial-patch — omit to leave unchanged. To convert cron ↔ one-shot, "
                + "supply BOTH the new trigger AND set the old one to null (the BE distinguishes "
                + "\"absent\" from \"present-as-null\" via the field key in the request body).";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_id", Map.of("type", "integer", "description", "Required: task id to update"));
        properties.put("name", Map.of("type", "string"));
        properties.put("agent_id", Map.of("type", "integer"));
        properties.put("cron_expr", Map.of(
                "type", "string",
                "description", "Set null to clear (use with one_shot_at to convert)"
        ));
        properties.put("one_shot_at", Map.of(
                "type", "string",
                "description", "ISO-8601 instant; set null to clear (use with cron_expr to convert)"
        ));
        properties.put("timezone", Map.of("type", "string"));
        properties.put("prompt_template", Map.of("type", "string"));
        properties.put("session_mode", Map.of("type", "string", "enum", List.of("new", "reuse")));
        properties.put("channel_target", Map.of(
                "type", "object",
                "description", "{channelType, channelId} or null to clear"
        ));
        properties.put("enabled", Map.of("type", "boolean", "description", "Toggle on/off"));

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
        if (input == null) {
            return SkillResult.validationError("input is required");
        }
        Long taskId = ScheduledTaskToolSupport.toLong(input.get("task_id"));
        if (taskId == null) {
            return SkillResult.validationError("task_id is required");
        }
        try {
            ScheduledTaskRequest req = new ScheduledTaskRequest();
            if (input.containsKey("name")) req.setName(ScheduledTaskToolSupport.toString(input.get("name")));
            if (input.containsKey("agent_id")) req.setAgentId(ScheduledTaskToolSupport.toLong(input.get("agent_id")));
            if (input.containsKey("cron_expr")) req.setCronExpr(ScheduledTaskToolSupport.toString(input.get("cron_expr")));
            if (input.containsKey("one_shot_at")) req.setOneShotAt(ScheduledTaskToolSupport.toInstant(input.get("one_shot_at")));
            if (input.containsKey("timezone")) req.setTimezone(ScheduledTaskToolSupport.toString(input.get("timezone")));
            if (input.containsKey("prompt_template")) req.setPromptTemplate(ScheduledTaskToolSupport.toString(input.get("prompt_template")));
            if (input.containsKey("session_mode")) req.setSessionMode(ScheduledTaskToolSupport.toString(input.get("session_mode")));
            if (input.containsKey("channel_target")) {
                req.setChannelTarget(ScheduledTaskToolSupport.coerceChannelTarget(
                        input.get("channel_target"), objectMapper));
            }
            if (input.containsKey("enabled")) req.setEnabled(ScheduledTaskToolSupport.toBoolean(input.get("enabled")));

            ScheduledTaskEntity updated = scheduledTaskService.update(taskId, userId, req);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", updated.getId());
            response.put("nextFireAt", updated.getNextFireAt());
            response.put("status", "updated");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("UpdateScheduledTask execute failed (userId={}, taskId={}): {}", userId, taskId, e.getMessage());
            return ScheduledTaskToolSupport.mapException(e);
        }
    }
}
