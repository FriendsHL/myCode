package com.skillforge.server.tool.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.ScheduledTaskRequest;
import com.skillforge.server.entity.ScheduledTaskEntity;
import com.skillforge.server.service.ScheduledTaskService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P12 agent tool: creates a new scheduled task on behalf of the current user.
 *
 * <p>{@code creator_user_id} is forced from {@link SkillContext#getUserId()};
 * the agent cannot create schedules for another user. {@code agentId} defaults
 * to the current session's agent if absent. Exactly one of
 * {@code cron_expr} / {@code one_shot_at} is required (DB CHECK + service validation).
 *
 * <p>Silent — does not request approval. Owner enforcement happens in
 * {@link ScheduledTaskService}.
 */
public class CreateScheduledTaskTool implements Tool {

    public static final String NAME = "CreateScheduledTask";

    private static final Logger log = LoggerFactory.getLogger(CreateScheduledTaskTool.class);

    private final ScheduledTaskService scheduledTaskService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public CreateScheduledTaskTool(ScheduledTaskService scheduledTaskService,
                                   SessionService sessionService,
                                   ObjectMapper objectMapper) {
        this.scheduledTaskService = scheduledTaskService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Create a new scheduled task that fires a prompt on a cron schedule "
                + "or at a one-shot time. Required: name, prompt_template, and exactly "
                + "one of cron_expr (Spring 6-field cron) or one_shot_at (ISO-8601 instant). "
                + "Optional: agent_id (default current session's agent), timezone (default "
                + "Asia/Shanghai), session_mode (\"new\" creates a fresh session per fire, "
                + "\"reuse\" keeps one session across fires; default \"new\"), channel_target "
                + "({channelType, channelId} for push notification on completion), enabled "
                + "(default true). The schedule fires under the current user's identity.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Display name (≤128 chars)"));
        properties.put("prompt_template", Map.of(
                "type", "string",
                "description", "Prompt sent as the user message at every fire (plain text, no variables in MVP)"
        ));
        properties.put("cron_expr", Map.of(
                "type", "string",
                "description", "Spring 6-field cron expression (e.g. \"0 0 9 * * *\"). Mutually exclusive with one_shot_at."
        ));
        properties.put("one_shot_at", Map.of(
                "type", "string",
                "description", "ISO-8601 instant (e.g. \"2026-12-25T09:00:00Z\") for one-shot schedule. Mutually exclusive with cron_expr."
        ));
        properties.put("agent_id", Map.of(
                "type", "integer",
                "description", "Agent the schedule fires (default: current session's agent)"
        ));
        properties.put("timezone", Map.of(
                "type", "string",
                "description", "IANA timezone for cron evaluation (default Asia/Shanghai)"
        ));
        properties.put("session_mode", Map.of(
                "type", "string",
                "description", "new = fresh session per fire; reuse = same session across fires (compaction handled automatically)",
                "enum", List.of("new", "reuse")
        ));
        properties.put("channel_target", Map.of(
                "type", "object",
                "description", "Push target for fire results: {channelType: \"feishu\", channelId: \"oc_xxx\"}. Omit to skip channel push."
        ));
        properties.put("enabled", Map.of(
                "type", "boolean",
                "description", "Default true. Set false to create paused."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "prompt_template"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        Long userId = ScheduledTaskToolSupport.requireUserId(context);
        if (userId == null) {
            return ScheduledTaskToolSupport.userIdMissingError();
        }
        if (input == null || input.isEmpty()) {
            return SkillResult.validationError("input is required");
        }
        try {
            ScheduledTaskRequest req = new ScheduledTaskRequest();
            req.setName(ScheduledTaskToolSupport.toString(input.get("name")));
            req.setPromptTemplate(ScheduledTaskToolSupport.toString(input.get("prompt_template")));

            Long agentId = ScheduledTaskToolSupport.resolveAgentId(
                    input.get("agent_id"), context, sessionService);
            if (agentId == null) {
                return SkillResult.validationError(
                        "agent_id is required (no current session's agent to fall back to)");
            }
            req.setAgentId(agentId);

            // Trigger fields — let setters auto-flag presence.
            if (input.containsKey("cron_expr")) {
                req.setCronExpr(ScheduledTaskToolSupport.toString(input.get("cron_expr")));
            }
            if (input.containsKey("one_shot_at")) {
                req.setOneShotAt(ScheduledTaskToolSupport.toInstant(input.get("one_shot_at")));
            }
            if (input.containsKey("timezone")) {
                req.setTimezone(ScheduledTaskToolSupport.toString(input.get("timezone")));
            }
            if (input.containsKey("session_mode")) {
                req.setSessionMode(ScheduledTaskToolSupport.toString(input.get("session_mode")));
            }
            if (input.containsKey("channel_target")) {
                req.setChannelTarget(ScheduledTaskToolSupport.coerceChannelTarget(
                        input.get("channel_target"), objectMapper));
            }
            if (input.containsKey("enabled")) {
                req.setEnabled(ScheduledTaskToolSupport.toBoolean(input.get("enabled")));
            }

            ScheduledTaskEntity created = scheduledTaskService.create(userId, req);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", created.getId());
            response.put("nextFireAt", created.getNextFireAt());
            response.put("status", "created");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("CreateScheduledTask execute failed (userId={}): {}", userId, e.getMessage());
            return ScheduledTaskToolSupport.mapException(e);
        }
    }
}
