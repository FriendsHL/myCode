package com.skillforge.server.flywheel.run;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPT-LOOP-FRAMEWORK Sprint 4 — REST surface for the dashboard
 * {@code /flywheel-runs} page (FR-5). Serves a generic list+detail view over
 * every loop_kind row in {@code t_flywheel_run} — OPT-REPORT, memory_curation,
 * attribution, ...
 *
 * <p><b>Namespace</b>: <code>/api/flywheel/orchestrator-runs</code>. Sprint 4
 * Plan §1 + §8 R1: the original prd FR-5 URL {@code /api/flywheel/runs} is
 * already taken by FLYWHEEL-PER-RUN ({@code FlywheelController}) returning the
 * OptimizationEvent-per-run view, so we land in a fresh namespace to avoid
 * shape collisions (538b828 / 5e25067 footgun precedent).
 *
 * <p><b>Outer envelope shape</b> (java footgun #6b — reviewer MUST verify):
 * <ul>
 *   <li>{@code GET /} →
 *       <code>{ "items": [...], "total": N, "limit": K, "offset": M }</code>
 *       — built via {@link LinkedHashMap} so the JSON field order is stable
 *       (Map.of() is unordered).</li>
 *   <li>{@code GET /{id}} →
 *       <code>{ "run": {...}, "steps": [...] }</code> or 404.</li>
 * </ul>
 * The FE TS {@code ListFlywheelOrchestratorRunsResponse} /
 * {@code FlywheelOrchestratorRunDetailResponse} interfaces mirror these shapes
 * 1-to-1; mocks in vitest must also mirror them (no echo-chamber {@code data:[...]}
 * forms).
 *
 * <p>Auth: V1 single-tenant dogfood pattern — same Bearer-token AuthInterceptor
 * the rest of {@code /api/**} uses (no per-endpoint auth annotations needed).
 */
@RestController
@RequestMapping("/api/flywheel/orchestrator-runs")
public class FlywheelOrchestratorRunController {

    static final int DEFAULT_LIMIT = 20;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;
    static final int MIN_OFFSET = 0;

    private final FlywheelRunService flywheelRunService;

    public FlywheelOrchestratorRunController(FlywheelRunService flywheelRunService) {
        this.flywheelRunService = flywheelRunService;
    }

    /**
     * List runs with optional filters (any combination of loopKind / agentId /
     * status) + offset-based pagination. Returns an envelope with
     * {@code items} + {@code total} + {@code limit} + {@code offset} so the FE
     * can render "X of Y" pagination indicators.
     *
     * <p>Empty filters returns all runs newest-first. Limit clamps to [1, 100];
     * offset clamps to >= 0. Empty-string filter params are treated as null
     * (i.e. ignored).
     */
    @GetMapping("")
    public ResponseEntity<?> list(
            @RequestParam(value = "loopKind", required = false) String loopKind,
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {

        // Treat empty strings as "no filter" so the FE can send blank dropdown
        // values without a URL-strip step.
        String loopKindFilter = blankToNull(loopKind);
        String statusFilter = blankToNull(status);

        int safeLimit = clampLimit(limit);
        int safeOffset = clampOffset(offset);

        Page<FlywheelRunEntity> pageResult = flywheelRunService.listRuns(
                loopKindFilter, agentId, statusFilter, safeLimit, safeOffset);

        List<FlywheelOrchestratorRunDto> items = new ArrayList<>(pageResult.getNumberOfElements());
        for (FlywheelRunEntity r : pageResult.getContent()) {
            items.add(FlywheelOrchestratorRunDto.from(r));
        }

        // LinkedHashMap (not Map.of()) so the JSON field order is stable:
        // items → total → limit → offset. Matches the FE TS interface
        // declaration order and the W2 plan-review note.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", pageResult.getTotalElements());
        body.put("limit", safeLimit);
        body.put("offset", safeOffset);
        return ResponseEntity.ok(body);
    }

    /**
     * Detail endpoint: run row + its chronological step list ({@code created_at}
     * ASC). 404 when the run id doesn't exist.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") String id) {
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
        }
        FlywheelRunEntity run = flywheelRunService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "FlywheelRun not found: id=" + id));

        List<FlywheelRunStepEntity> stepRows = flywheelRunService.listStepsByRunId(id);
        List<FlywheelOrchestratorStepDto> steps = new ArrayList<>(stepRows.size());
        for (FlywheelRunStepEntity s : stepRows) {
            steps.add(FlywheelOrchestratorStepDto.from(s));
        }

        // LinkedHashMap envelope: run → steps. Stable field order matters
        // because the FE TS interface declares them in this order, and
        // because the contract IT below pattern-matches the raw JSON string.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", FlywheelOrchestratorRunDto.from(run));
        body.put("steps", steps);
        return ResponseEntity.ok(body);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (raw < MIN_LIMIT) return MIN_LIMIT;
        return Math.min(raw, MAX_LIMIT);
    }

    private static int clampOffset(Integer raw) {
        if (raw == null) return MIN_OFFSET;
        return Math.max(raw, MIN_OFFSET);
    }
}
