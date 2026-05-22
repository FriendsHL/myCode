package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ANNOTATOR-BEHAVIOR-SIGNALS (2026-05-22): STEP 1.5 of the session-annotator pipeline.
 *
 * <p>Given a {@code sessionId} (from STEP 1's {@code sessions_needing_llm} list),
 * queries {@code t_llm_span} + {@code t_llm_trace} to compute deterministic
 * behavioral efficiency stats that the LLM annotator (STEP 2.2) can use beyond
 * just the trace span tree:
 * <ul>
 *   <li>{@code totalTurns} — count of {@code kind='llm'} spans (one per Agent Loop turn)</li>
 *   <li>{@code totalDurationMs} — latest trace's {@code total_duration_ms} for the session</li>
 *   <li>{@code perToolCounts} — top-5 tools by invocation count ({@code kind='tool'}, GROUP BY name)</li>
 *   <li>{@code errorSpanCount} — number of spans with non-null {@code error} column</li>
 *   <li>{@code topTool} / {@code topToolCount} — convenience extraction from {@code perToolCounts}</li>
 *   <li>{@code hasToolOveruse} — {@code topToolCount > 10}</li>
 *   <li>{@code hasLoopInefficiency} — {@code totalTurns > 20}</li>
 * </ul>
 *
 * <p>Thresholds (10 / 20) are V1 heuristics surfaced into the prompt so the LLM
 * makes the final outcome call; behavioral flags are <em>hints</em> not
 * verdicts. Tuning lives in the prompt (STEP 2.2 reasoning) not in this tool.
 *
 * <p>Returns success with zeroed stats (empty {@code perToolCounts}) when the
 * session has no spans yet — caller's prompt explicitly instructs "若返回空，
 * 跳过 STEP 2" so the missing-data branch stays the agent's responsibility, not
 * a tool error.
 */
public class SpanBehaviorStatsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SpanBehaviorStatsTool.class);

    private static final int TOP_TOOL_COUNT_LIMIT = 5;
    private static final int TOOL_OVERUSE_THRESHOLD = 10;
    private static final int LOOP_INEFFICIENCY_THRESHOLD = 20;

    private final LlmSpanRepository spanRepository;
    private final LlmTraceRepository traceRepository;
    private final ObjectMapper objectMapper;

    public SpanBehaviorStatsTool(LlmSpanRepository spanRepository,
                                 LlmTraceRepository traceRepository,
                                 ObjectMapper objectMapper) {
        this.spanRepository = spanRepository;
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "SpanBehaviorStats";
    }

    @Override
    public String getDescription() {
        return "STEP 1.5 of the session-annotator pipeline. Given a sessionId, queries "
                + "t_llm_span to compute per-tool call counts, total LLM turns, error span count, "
                + "and derives behavioral flags (hasToolOveruse: topToolCount > "
                + TOOL_OVERUSE_THRESHOLD + ", hasLoopInefficiency: totalTurns > "
                + LOOP_INEFFICIENCY_THRESHOLD + "). Call this before AnnotateSession to "
                + "give the LLM annotator behavioral efficiency context beyond just the trace tree.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "t_session.id (VARCHAR 36) — the session to inspect."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sessionId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String sessionId = null;
            if (input != null) {
                Object raw = input.get("sessionId");
                if (raw != null) {
                    sessionId = raw.toString().trim();
                }
            }
            if (sessionId == null || sessionId.isEmpty()) {
                return SkillResult.validationError("sessionId is required");
            }

            // totalTurns — count of kind='llm' spans (each LLM call is one Agent Loop turn).
            long totalTurns = spanRepository.countBySessionIdAndKind(sessionId, "llm");

            // errorSpanCount — count of spans with non-null error column.
            long errorSpanCount = spanRepository.countBySessionIdAndErrorIsNotNull(sessionId);

            // perToolCounts — top-N by invocation count (descending). NULL tool names
            // (legacy pre-OBS-2 M0 rows) skipped — LLM annotator cannot reason about
            // unnamed tools.
            List<Object[]> rawCounts = spanRepository.countToolCallsByNameForSession(sessionId);
            List<Map<String, Object>> perToolCounts = new ArrayList<>();
            String topTool = null;
            long topToolCount = 0L;
            for (Object[] row : rawCounts) {
                if (row == null || row.length < 2 || row[0] == null) continue;
                String name = row[0].toString();
                long count = ((Number) row[1]).longValue();
                if (perToolCounts.size() < TOP_TOOL_COUNT_LIMIT) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("tool", name);
                    entry.put("count", count);
                    perToolCounts.add(entry);
                }
                if (count > topToolCount) {
                    topTool = name;
                    topToolCount = count;
                }
            }

            // totalDurationMs — latest trace's total_duration_ms for the session
            // (prod sessions usually have a single trace; pick the most recent).
            long totalDurationMs = 0L;
            List<LlmTraceEntity> traces = traceRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
            if (traces != null && !traces.isEmpty()) {
                totalDurationMs = traces.get(0).getTotalDurationMs();
            }

            boolean hasToolOveruse = topToolCount > TOOL_OVERUSE_THRESHOLD;
            boolean hasLoopInefficiency = totalTurns > LOOP_INEFFICIENCY_THRESHOLD;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("totalTurns", totalTurns);
            payload.put("totalDurationMs", totalDurationMs);
            payload.put("perToolCounts", perToolCounts);
            payload.put("errorSpanCount", errorSpanCount);
            payload.put("topTool", topTool);
            payload.put("topToolCount", topToolCount);
            payload.put("hasToolOveruse", hasToolOveruse);
            payload.put("hasLoopInefficiency", hasLoopInefficiency);

            log.info("SpanBehaviorStatsTool: sessionId={} totalTurns={} totalDurationMs={} "
                            + "errorSpanCount={} topTool={} topToolCount={} hasToolOveruse={} hasLoopInefficiency={}",
                    sessionId, totalTurns, totalDurationMs, errorSpanCount, topTool, topToolCount,
                    hasToolOveruse, hasLoopInefficiency);

            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("SpanBehaviorStatsTool execute failed", e);
            return SkillResult.error("SpanBehaviorStats error: " + e.getMessage());
        }
    }
}
