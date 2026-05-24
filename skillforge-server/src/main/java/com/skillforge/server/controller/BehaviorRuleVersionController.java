package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.controller.dto.BehaviorRuleAbRunResponse;
import com.skillforge.server.controller.dto.BehaviorRuleVersionResponse;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.improve.BehaviorRulePromotionService.PromoteResult;
import com.skillforge.server.improve.behavior.BehaviorRuleAbEvalService;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V4 Phase 1.4 — REST endpoints for {@link BehaviorRuleVersionEntity}.
 *
 * <p>Separated from {@link BehaviorRuleController} (which serves the static
 * built-in rule definitions registry) because version data is row-backed,
 * agent-scoped, and queried with different filters than the rule registry.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/behavior-rules/versions?agentId={id}&status={status}}
 *       — list versions for an agent (newest-first by versionNumber).
 *       {@code status} is optional; when present filters to the matching
 *       subset ({@code candidate} / {@code active} / {@code retired} /
 *       {@code rejected}).</li>
 *   <li>{@code GET /api/behavior-rules/versions/{id}} — single version by id.
 *       Returns 404 when not found.</li>
 * </ul>
 *
 * <p>Auth: follows the existing {@link BehaviorRuleController} pattern (no
 * explicit owner check in the controller — relies on global Spring Security
 * filter chain if configured). When auth becomes required, the natural place
 * is a {@code @PreAuthorize} on each method using the {@code agentId} query
 * param / path variable.
 */
@RestController
@RequestMapping("/api/behavior-rules/versions")
public class BehaviorRuleVersionController {

    private final BehaviorRuleVersionRepository versionRepository;
    private final BehaviorRuleAbRunRepository abRunRepository;
    private final BehaviorRuleAbEvalService abEvalService;
    private final BehaviorRulePromotionService promotionService;
    private final ObjectMapper objectMapper;

    public BehaviorRuleVersionController(BehaviorRuleVersionRepository versionRepository,
                                          BehaviorRuleAbRunRepository abRunRepository,
                                          BehaviorRuleAbEvalService abEvalService,
                                          BehaviorRulePromotionService promotionService,
                                          ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.abRunRepository = abRunRepository;
        this.abEvalService = abEvalService;
        this.promotionService = promotionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<BehaviorRuleVersionResponse>> list(
            @RequestParam String agentId,
            @RequestParam(required = false) String status) {
        if (agentId == null || agentId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<BehaviorRuleVersionEntity> rows = (status == null || status.isBlank())
                ? versionRepository.findByAgentIdOrderByVersionNumberDesc(agentId)
                : versionRepository.findByAgentIdAndStatusOrderByVersionNumberDesc(agentId, status);
        List<BehaviorRuleVersionResponse> body = rows.stream()
                .map(BehaviorRuleVersionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BehaviorRuleVersionResponse> getOne(@PathVariable String id) {
        return versionRepository.findById(id)
                .map(BehaviorRuleVersionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────
    // BEHAVIOR-RULE-AB-EVAL V1: run-ab / promote / latest-ab-run endpoints.
    // Outer shape per java.md footgun #6b: each returns a single object,
    // not an envelope. FE wrapper signatures must match exactly:
    //   - runAb        → { abRunId: string }
    //   - promote      → { status: "promoted" | "noop", reason: string }
    //   - latestAbRun  → BehaviorRuleAbRunResponse (single object, 404 when absent)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * BEHAVIOR-RULE-AB-EVAL V1 r2-BE-1 fix — Spring's default 500-on-RuntimeException
     * was unusable: {@code promoteManual} / {@code startAbForVersion} throw
     * {@link IllegalStateException} for user-recoverable conditions (dual-criteria
     * not yet met, no completed run, non-candidate state, no dataset). FE wrapper
     * shows {@code Modal.confirm} + error toast and expects HTTP 4xx + JSON body
     * {@code {reason: "<message>"}}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@link IllegalArgumentException} (version not found) → <b>404</b> + reason</li>
     *   <li>{@link IllegalStateException} (state precondition fail) → <b>400</b> + reason</li>
     * </ul>
     *
     * <p>No project-wide {@code @RestControllerAdvice} exists (verified by grep);
     * adding one for this single Controller would introduce broader infrastructure
     * touching unrelated endpoints. Local try-catch is the minimum-blast-radius fix.
     */
    @PostMapping("/{id}/run-ab")
    public ResponseEntity<?> runAb(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (id == null || id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("reason", "id is required"));
        }
        String overrideDatasetVersionId = null;
        if (body != null) {
            Object raw = body.get("datasetVersionId");
            if (raw != null) {
                String s = String.valueOf(raw).trim();
                if (!s.isEmpty()) overrideDatasetVersionId = s;
            }
        }
        try {
            String abRunId = abEvalService.startAbForVersion(id, overrideDatasetVersionId);
            return ResponseEntity.ok(Map.of("abRunId", abRunId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("reason", safe(ex.getMessage())));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", safe(ex.getMessage())));
        }
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable String id) {
        if (id == null || id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("reason", "id is required"));
        }
        try {
            // triggeredByUserId left null until SecurityContext wiring lands —
            // mirrors BehaviorRuleController convention.
            PromoteResult r = promotionService.promoteManual(id, null);
            return ResponseEntity.ok(Map.of(
                    "status", r.status(),
                    "reason", r.reason() == null ? "" : r.reason()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("reason", safe(ex.getMessage())));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", safe(ex.getMessage())));
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /**
     * BEHAVIOR-RULE-AB-EVAL V1 — FE-BE contract C4 (locked-in behavior):
     * returns {@code 200 OK} with {@code null} body when no A/B run exists
     * for the version yet (instead of 404). FE wrapper signature is
     * {@code api.get<BehaviorRuleAbRun | null>(...)} — saves a try/catch on
     * the FE for a non-error case (no run yet is the normal initial state).
     *
     * <p>r2-BE-4: sort moved to repository derived query
     * {@link BehaviorRuleAbRunRepository#findFirstByCandidateVersionIdOrderByStartedAtDesc};
     * controller stays thin.
     */
    @GetMapping("/{id}/latest-ab-run")
    public ResponseEntity<BehaviorRuleAbRunResponse> latestAbRun(@PathVariable String id) {
        if (id == null || id.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        BehaviorRuleAbRunEntity latest = abRunRepository
                .findFirstByCandidateVersionIdOrderByStartedAtDesc(id)
                .orElse(null);
        // C4: 200 + null body (NOT 404). C3: ObjectMapper-aware overload so
        // scenarioResults populates from abScenarioResultsJson.
        return ResponseEntity.ok(BehaviorRuleAbRunResponse.from(latest, objectMapper));
    }
}
