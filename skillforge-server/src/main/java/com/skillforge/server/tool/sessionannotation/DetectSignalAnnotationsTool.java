package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.sessionannotation.SessionAnnotationSignalService;
import com.skillforge.server.sessionannotation.SessionAnnotationSignalService.SessionNeedingLlmDto;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.2): thin agent-facing wrapper around
 * {@link SessionAnnotationSignalService#detectAndPersist(Duration)}.
 *
 * <p>STEP 1 of the {@code session-annotator} agent pipeline (§4.1
 * "Signal detection — deterministic"). The agent invokes this once per run,
 * defaults to {@code window_hours=1} (matches the hourly V75 cron); the tool
 * persists {@code source='signal'} annotations and returns the queue of
 * sessions that need LLM-derived {@code outcome}/{@code suspect_surface}
 * (capped at 10 per §4.3 brief).
 *
 * <p>All business logic lives in the service — this tool is the JSON adapter
 * (parse window_hours, format output) and an LLM-facing description. The
 * service unit tests lock the main behavior; this tool's tests verify wiring.
 */
public class DetectSignalAnnotationsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DetectSignalAnnotationsTool.class);

    private static final int DEFAULT_WINDOW_HOURS = 1;
    private static final int MIN_WINDOW_HOURS = 1;
    private static final int MAX_WINDOW_HOURS = 168;  // 7d — defensive ceiling for V1
    private static final int LLM_QUEUE_LIMIT = 10;     // matches §4.1 STEP 2 cap

    private final SessionAnnotationSignalService signalService;
    private final ObjectMapper objectMapper;

    public DetectSignalAnnotationsTool(SessionAnnotationSignalService signalService,
                                       ObjectMapper objectMapper) {
        this.signalService = signalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "DetectSignalAnnotations";
    }

    @Override
    public String getDescription() {
        return "STEP 1 of the session-annotator pipeline. Scan production sessions "
                + "completed in the last N hours (default " + DEFAULT_WINDOW_HOURS
                + "), call deterministic signal-reason detection (agent_error / "
                + "tool_failure / span_error / high_token / multi_turn / has_tool_calls), "
                + "and persist source='signal' rows into t_session_annotation. Idempotent "
                + "on re-run via UNIQUE constraint. Returns the next batch of sessions "
                + "(cap " + LLM_QUEUE_LIMIT + ") that still need source='llm' annotation "
                + "from STEP 2 (AnnotateSession). No LLM judgment performed here. "
                + "Optional agent_id restricts the scan to a single agent's sessions "
                + "(for the on-demand per-agent loop trigger); omit it for the "
                + "default cron behavior that scans every production agent.";
    }

    @Override
    public boolean isReadOnly() {
        // Writes t_session_annotation. Not read-only — keep default false.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("window_hours", Map.of(
                "type", "integer",
                "description", "Look-back window in hours (default " + DEFAULT_WINDOW_HOURS
                        + ", clamped to [" + MIN_WINDOW_HOURS + ", " + MAX_WINDOW_HOURS + "])."));
        // FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21): optional scope filter. The
        // hourly cron path never sets this (user message "Process all"); the
        // on-demand per-agent loop trigger sets agent_id=<id> in the user
        // message so the agent threads it to this tool.
        properties.put("agent_id", Map.of(
                "type", "integer",
                "description", "Optional agent id to restrict the scan to a single "
                        + "agent's sessions. Omit it for the default cron behavior "
                        + "that scans every production agent."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // window_hours + agent_id intentionally not required — sensible defaults keep the agent prompt simple.
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            int windowHours = DEFAULT_WINDOW_HOURS;
            Long agentIdFilter = null;
            if (input != null) {
                windowHours = SkillInputUtils.toInt(input.get("window_hours"), DEFAULT_WINDOW_HOURS);
                Object agentIdRaw = input.get("agent_id");
                if (agentIdRaw != null) {
                    // toLong returns null on unparseable input (e.g. LLM wrote
                    // "all" or omitted the value as a string). Treat null +
                    // 0 + negative all as "drop the filter, scan everything"
                    // — caller wanting the on-demand scope must supply a real
                    // positive agent id.
                    Long parsed = SkillInputUtils.toLong(agentIdRaw);
                    if (parsed != null && parsed > 0L) {
                        agentIdFilter = parsed;
                    }
                }
            }
            if (windowHours < MIN_WINDOW_HOURS) windowHours = MIN_WINDOW_HOURS;
            if (windowHours > MAX_WINDOW_HOURS) windowHours = MAX_WINDOW_HOURS;

            int signalCount = signalService.detectAndPersist(Duration.ofHours(windowHours), agentIdFilter);
            // r2 fix (code-reviewer W2 scope leak): when agentIdFilter is set,
            // the LLM queue must also filter by agent — otherwise on-demand
            // "Run loop on agent X" silently annotates other agents' pending
            // sessions. cron path (agentIdFilter=null) preserves 3-tier behaviour.
            List<SessionNeedingLlmDto> queue = signalService.findSessionsNeedingLlmAnnotation(LLM_QUEUE_LIMIT, agentIdFilter);

            List<Map<String, Object>> queueOut = new ArrayList<>(queue.size());
            for (SessionNeedingLlmDto dto : queue) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", dto.sessionId());
                row.put("agentName", dto.agentName());
                row.put("signalReasons", dto.signalReasons());
                queueOut.add(row);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("signal_count", signalCount);
            payload.put("window_hours", windowHours);
            payload.put("agent_id", agentIdFilter);
            payload.put("sessions_needing_llm", queueOut);
            log.info("DetectSignalAnnotationsTool: windowHours={} agentIdFilter={} signalCount={} queueSize={}",
                    windowHours, agentIdFilter, signalCount, queueOut.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("DetectSignalAnnotationsTool execute failed", e);
            return SkillResult.error("DetectSignalAnnotations error: " + e.getMessage());
        }
    }
}
