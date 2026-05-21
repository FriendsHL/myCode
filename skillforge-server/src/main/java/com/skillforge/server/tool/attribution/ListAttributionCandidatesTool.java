package com.skillforge.server.tool.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.attribution.AttributionDispatcherService;
import com.skillforge.server.attribution.AttributionDispatcherService.CandidateEntry;
import com.skillforge.server.attribution.AttributionDispatcherService.CandidateListResult;
import com.skillforge.server.util.SkillInputUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DISPATCHER-ORCHESTRATOR-REFACTOR — Tool wrapper around
 * {@link AttributionDispatcherService#listAndReserveCandidates(int)}. Used by
 * the {@code attribution-dispatcher} system agent as STEP 1 of its 4-step
 * orchestration loop (replaces the legacy {@code DispatchAttributionPatterns}
 * Tool that fan-out'ed Java-side).
 *
 * <p>Why this exists: the prior dispatcher tool did all the work in one Java
 * call (scan + filter + sentinel + per-pattern chatAsync(attribution-curator))
 * — the LLM was just an outer shell that immediately summarised the result.
 * The new design hoists per-pattern dispatch decisions into the LLM: this tool
 * scans + filters + reserves sentinels and returns the candidates list; the
 * LLM then walks the list and uses {@code SubAgent} (action=dispatch,
 * agentName=attribution-curator) to spawn one curator per candidate it
 * actually wants to route. Per-pattern routing decisions (e.g. outcome ∈
 * {failure, partial_success, cancelled, infrastructure_failure, cost_high}
 * → curator; success → skip) thus live in the dispatcher's system prompt
 * (configurable at runtime) instead of being baked into Java.
 *
 * <p>Wire shape:
 * <ul>
 *   <li>input: {@code { "max": int (optional, default 10, clamped to [1,20]) }}</li>
 *   <li>output: {@code { "ok": true, "candidates": [...], "total_scanned": N,
 *       "filtered_out": M, "reserved_count": K }} where each candidate entry
 *       carries {@code patternId / sentinelEventId / signature / outcome /
 *       surface / memberCount / lastSeenAt}; {@code reserved_count} equals
 *       {@code candidates.size()} (number of sentinels written for this run —
 *       orphan sentinels are swept by
 *       {@link AttributionDispatcherService#cleanupOrphanSentinels()} when the
 *       LLM never routes them).</li>
 *   <li>error path: any {@link Exception} thrown by the service is mapped to
 *       {@link SkillResult#error(String)} with prefix
 *       {@code "ListAttributionCandidates: "} so the dispatcher LLM still gets
 *       a structured signal to emit its final summary instead of dying
 *       mid-loop.</li>
 * </ul>
 */
public class ListAttributionCandidatesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListAttributionCandidatesTool.class);

    static final int DEFAULT_MAX = 10;
    static final int MIN_MAX = 1;
    static final int MAX_MAX = 20;

    private final AttributionDispatcherService dispatcherService;
    private final ObjectMapper objectMapper;

    public ListAttributionCandidatesTool(AttributionDispatcherService dispatcherService,
                                         ObjectMapper objectMapper) {
        this.dispatcherService = dispatcherService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ListAttributionCandidates";
    }

    @Override
    public String getDescription() {
        return "STEP 1 entry point for the attribution-dispatcher system agent. "
                + "Scans t_session_pattern, applies the 4 ratify-locked filters "
                + "(surface allowlist / member-count threshold / 24h cooldown / "
                + "no in-flight event), writes a dispatch_initiated sentinel row "
                + "for each surviving candidate (so concurrent dispatcher ticks "
                + "do not double-route the same pattern), and returns the "
                + "candidates list. The agent itself walks the list and uses "
                + "SubAgent(action=dispatch, agentName=attribution-curator, "
                + "prompt=...) to spawn one curator per candidate it routes. "
                + "Unrouted candidates leave their sentinels behind — "
                + "cleanupOrphanSentinels() sweeps them at the next hour. "
                + "max is optional (default " + DEFAULT_MAX
                + ", clamped to [" + MIN_MAX + ", " + MAX_MAX + "]).";
    }

    @Override
    public boolean isReadOnly() {
        // Writes dispatch_initiated sentinel rows → mutates state.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("max", Map.of(
                "type", "integer",
                "description", "Optional cap on candidates to reserve this run "
                        + "(default " + DEFAULT_MAX + ", clamped to ["
                        + MIN_MAX + ", " + MAX_MAX + "])."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of());
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        int max = DEFAULT_MAX;
        if (input != null) {
            max = SkillInputUtils.toInt(input.get("max"), DEFAULT_MAX);
        }
        // Defensive clamp: LLM-supplied input is untrusted. Caller asking
        // "0 or negative" is honored with MIN_MAX (1) rather than silently
        // expanding to a service default — predictability beats legacy
        // service-side fallback behavior.
        max = Math.max(MIN_MAX, Math.min(MAX_MAX, max));

        try {
            CandidateListResult result = dispatcherService.listAndReserveCandidates(max);
            List<Map<String, Object>> items = new ArrayList<>(result.candidates().size());
            for (CandidateEntry c : result.candidates()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patternId", c.patternId());
                row.put("sentinelEventId", c.sentinelEventId());
                row.put("signature", c.signature());
                row.put("outcome", c.outcome());
                row.put("surface", c.surface());
                row.put("memberCount", c.memberCount());
                // Instant → ISO string via Jackson default (JavaTimeModule is
                // registered on the project ObjectMapper). Keeps the wire
                // shape consistent with every other Tool that serialises
                // timestamps for LLM consumption.
                row.put("lastSeenAt", c.lastSeenAt());
                items.add(row);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("candidates", items);
            payload.put("total_scanned", result.totalScanned());
            payload.put("filtered_out", result.filteredOut());
            payload.put("reserved_count", items.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // Mirrors the legacy DispatchAttributionPatternsTool W4 fix:
            // service-side I/O failure on the outer scan / agent lookup is
            // mapped to a SkillResult.error so the dispatcher LLM's final
            // STEP 4 summary can still emit structured output (instead of
            // the loop engine treating an exception as a hard abort).
            log.warn("[ListAttributionCandidatesTool] listAndReserveCandidates failed: {}",
                    e.getMessage(), e);
            return SkillResult.error("ListAttributionCandidates: " + e.getMessage());
        }
    }
}
