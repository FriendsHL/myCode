package com.skillforge.server.flywheel;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FLYWHEEL-PER-RUN — REST surface for the per-run sidebar of the Flywheel
 * observability panel.
 *
 * <p>Mode toggle in the dashboard's {@code /insights} 5th tab switches between
 * "Aggregate" (existing topology + metric panels, unchanged) and "Per-Run"
 * which consumes this endpoint to render the recent attribution runs.
 *
 * <p>Auth: V1 single-tenant dogfood pattern (matches AttributionEventController /
 * InsightsController). Goes through the same Bearer-token AuthInterceptor as
 * every other {@code /api/**} endpoint. Phase 2 may introduce role-based
 * gating consistent with the rest of the attribution API.
 */
@RestController
@RequestMapping("/api/flywheel")
public class FlywheelController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int MIN_LIMIT = 1;

    /**
     * Accepted values for the {@code agentType} query param — mirrors the
     * {@code chk_agent_type} DB CHECK constraint (V89). Unknown values fail
     * fast with 400 rather than silently returning {@code []}.
     */
    static final Set<String> ALLOWED_AGENT_TYPES = Set.of("user", "system");

    private final FlywheelRunsService runsService;

    public FlywheelController(FlywheelRunsService runsService) {
        this.runsService = runsService;
    }

    /**
     * Recent attribution runs (one row per {@code t_optimization_event}) with
     * agent + pattern context joined. {@code updated_at DESC} sort.
     *
     * <p>Query params:
     * <ul>
     *   <li>{@code agentType} — {@code user} / {@code system} (optional;
     *       resolves to an agent-id set via {@code agent_type} column).
     *       Blank → no filter.</li>
     *   <li>{@code surface} — {@code skill} / {@code prompt} /
     *       {@code behavior_rule} (optional). Blank → no filter.</li>
     *   <li>{@code limit} — default {@value #DEFAULT_LIMIT}, clamped to
     *       {@code [{@value #MIN_LIMIT}, {@value #MAX_LIMIT}]}.</li>
     *   <li>{@code hideTerminal} — default {@code true}: exclude runs in
     *       {@link FlywheelRunsService#TERMINAL_HAPPY_STAGES}
     *       (promoted / verified / rolled_back). Failed terminals
     *       (proposal_rejected / candidate_failed / ab_failed) stay visible —
     *       operators want to see errors.</li>
     * </ul>
     */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns(@RequestParam(value = "agentType", required = false) String agentType,
                                      @RequestParam(value = "surface", required = false) String surface,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      @RequestParam(value = "hideTerminal", required = false) Boolean hideTerminal) {
        String agentTypeNorm = blankToNull(agentType);
        // r2 W2 fix: fail-fast on unknown agentType rather than silently returning
        // [] (the service would have done so via empty agentIds, which masked
        // typo bugs at the FE).
        if (agentTypeNorm != null && !ALLOWED_AGENT_TYPES.contains(agentTypeNorm)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "agentType must be one of " + ALLOWED_AGENT_TYPES + " (got: " + agentTypeNorm + ")");
        }
        int safeLimit = clampLimit(limit);
        boolean safeHideTerminal = hideTerminal == null || hideTerminal;
        List<FlywheelRunDto> items = runsService.listRecentRuns(
                agentTypeNorm,
                blankToNull(surface),
                safeLimit,
                safeHideTerminal);

        // LinkedHashMap to keep the response field order stable in JSON output.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("limit", safeLimit);
        body.put("hideTerminal", safeHideTerminal);
        return ResponseEntity.ok(body);
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (raw < MIN_LIMIT) return MIN_LIMIT;
        return Math.min(raw, MAX_LIMIT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
