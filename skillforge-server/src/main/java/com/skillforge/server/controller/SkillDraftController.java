package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SkillDraftEntity;
import com.skillforge.server.improve.HighSimilarityRejectedException;
import com.skillforge.server.improve.SkillDraftService;
import com.skillforge.server.improve.SkillNameConflictException;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SkillDraftRepository;
import com.skillforge.server.skill.SkillCreatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class SkillDraftController {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftController.class);

    /** Phase 1.6 hotfix r6: thread-safe shared mapper for evaluationResult parse in toMap. */
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    private final SkillDraftService skillDraftService;
    private final SkillDraftRepository skillDraftRepository;
    private final ExecutorService coordinatorExecutor;
    /**
     * SKILL-CREATOR-PHASE-1.6 F2 (2026-05-19): operator-triggered eval gate
     * dependencies. Used by {@link #triggerDraftEvaluation} to wire ephemeral
     * scenarios from the draft's source sessions and fire
     * {@code SkillCreatorService.dispatchEvaluation}. All three are required;
     * the controller is only wired when Spring sees them (Phase 1.1+ runtime).
     */
    private final SkillCreatorService skillCreatorService;
    private final SessionRepository sessionRepository;
    private final EvalScenarioDraftRepository evalScenarioRepository;

    public SkillDraftController(SkillDraftService skillDraftService,
                                SkillDraftRepository skillDraftRepository,
                                @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                SkillCreatorService skillCreatorService,
                                SessionRepository sessionRepository,
                                EvalScenarioDraftRepository evalScenarioRepository) {
        this.skillDraftService = skillDraftService;
        this.skillDraftRepository = skillDraftRepository;
        this.coordinatorExecutor = coordinatorExecutor;
        this.skillCreatorService = skillCreatorService;
        this.sessionRepository = sessionRepository;
        this.evalScenarioRepository = evalScenarioRepository;
    }

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (2026-05-16) — manual /abtest-from-draft
     * endpoint exposed for operator-triggered A/B against an existing
     * SkillDraft. Auto-trigger path goes via
     * {@code OptimizationEventAutoTriggerListener.dispatchSkillAutoAb} which
     * calls the same {@link SkillDraftService#startAbTestFromDraft}.
     *
     * <p>Body schema:
     * <pre>{@code
     * {
     *   "candidateDraftId": "<uuid>",       // REQUIRED — V88 sidecar UUID
     *   "evalScenarioIds": ["<id>", ...]    // null/empty = ephemeral fallback
     * }
     * }</pre>
     *
     * <p>{@code parentSkillId} is currently unused (attribution path treats
     * parent as empty SKILL.md per ratify #7-B); kept in the URL for future
     * "improve existing skill" curator capability. Returns
     * {@code {"abRunId", "candidateDraftId", "parentSkillId"}}.
     */
    @PostMapping("/skills/{parentSkillId}/abtest-from-draft")
    public ResponseEntity<?> abtestFromDraft(@PathVariable Long parentSkillId,
                                              @RequestBody Map<String, Object> request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST",
                    "message", "request body is required"));
        }
        Object draftIdRaw = request.get("candidateDraftId");
        if (!(draftIdRaw instanceof String candidateDraftId) || candidateDraftId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST",
                    "message", "candidateDraftId is required"));
        }
        List<String> evalScenarioIds = null;
        Object esi = request.get("evalScenarioIds");
        if (esi instanceof List<?> l) {
            evalScenarioIds = l.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (evalScenarioIds.isEmpty()) evalScenarioIds = null;
        }

        try {
            String abRunId = skillDraftService.startAbTestFromDraft(candidateDraftId, evalScenarioIds);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("abRunId", abRunId);
            body.put("candidateDraftId", candidateDraftId);
            body.put("parentSkillId", parentSkillId);
            return ResponseEntity.accepted().body(body);
        } catch (IllegalArgumentException e) {
            // F3 fix (Phase 2 r2): log internal detail server-side; return
            // generic message client-side (java.md security). "not found"
            // string match preserved as the 404-vs-400 routing pivot (W2
            // message-text lock test asserts the routing, not the leaked
            // internal message).
            log.warn("[/abtest-from-draft] bad request parentSkillId={} draftId={}: {}",
                    parentSkillId, candidateDraftId, e.getMessage(), e);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean notFound = msg.contains("not found");
            return ResponseEntity.status(notFound ? 404 : 400)
                    .body(Map.of("error", notFound ? "NOT_FOUND" : "BAD_REQUEST",
                            "message", notFound ? "Resource not found" : "Invalid request"));
        } catch (IllegalStateException e) {
            log.warn("[/abtest-from-draft] conflict parentSkillId={} draftId={}: {}",
                    parentSkillId, candidateDraftId, e.getMessage(), e);
            return ResponseEntity.status(409).body(Map.of("error", "CONFLICT",
                    "message", "Operation conflicts with current state"));
        }
    }

    /**
     * Plan r2 §8 — userId required (was {@code ownerId} with default=0). agentId stays as
     * a path variable. The BE writes ownerId from the validated userId — never accepts an
     * ownerId input from FE.
     */
    @PostMapping("/agents/{agentId}/skill-drafts")
    public ResponseEntity<Map<String, Object>> triggerExtraction(
            @PathVariable Long agentId,
            @RequestParam(name = "userId", required = true) Long userId) {

        long pending = skillDraftService.countPendingDraftsForAgent(userId, agentId);
        if (pending > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_has_drafts",
                    "agentId", agentId,
                    "count", pending,
                    "message", "Review or discard existing drafts for this agent first"
            ));
        }
        // Legacy safeguard: drafts written before sourceSessionId was populated
        // can't be linked to an agent. Block all extractions until they're cleared
        // so they don't get drowned by new per-agent batches.
        long unattached = skillDraftService.countUnattachedPendingDrafts(userId);
        if (unattached > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_has_drafts",
                    "agentId", agentId,
                    "count", unattached,
                    "message", "Clear " + unattached + " legacy draft(s) (no agent link) before extracting"
            ));
        }

        coordinatorExecutor.submit(() -> {
            try {
                skillDraftService.extractFromRecentSessions(agentId, userId);
            } catch (Exception e) {
                log.error("Async skill draft extraction failed for agent {}: {}", agentId, e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "extracting",
                "agentId", agentId
        ));
    }

    /**
     * Plan r2 §8 — userId required. FLYWHEEL-VISUAL-STATUS Phase 2 — added
     * optional {@code source} filter (exact match on
     * {@link SkillDraftEntity#getSource()}; e.g. {@code upload} /
     * {@code marketplace} / {@code natural-language} /
     * {@code extract-from-sessions}). Free-form passthrough: unknown values
     * just match nothing rather than erroring, so the FE doesn't need an
     * allow-list compiled in.
     */
    @GetMapping("/skill-drafts")
    public ResponseEntity<List<Map<String, Object>>> listDrafts(
            @RequestParam(name = "userId", required = true) Long userId,
            @RequestParam(name = "source", required = false) String source) {
        List<Map<String, Object>> result = skillDraftService.getDrafts(userId, source)
                .stream().map(this::toMap).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/skill-drafts/{id}")
    public ResponseEntity<Map<String, Object>> reviewDraft(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        Object actionObj = body.get("action");
        String action = actionObj != null ? actionObj.toString() : null;
        // Plan r2 §8 — reviewedBy required; reject default=0 silent fallback.
        Long reviewedBy = body.containsKey("reviewedBy") && body.get("reviewedBy") != null
                ? Long.parseLong(body.get("reviewedBy").toString())
                : null;
        if (reviewedBy == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "reviewedBy is required"));
        }
        // Plan r2 §9 + Code Judge r1 B-FE-2 — forceCreate bypasses the high-similarity
        // gate after the FE Modal.confirm flow gets explicit operator acknowledgement.
        boolean forceCreate = body.containsKey("forceCreate")
                && Boolean.parseBoolean(String.valueOf(body.get("forceCreate")));

        // SKILL-DASHBOARD-POLISH-V2 §H — Rename branch of the merge UX modal.
        // When approving with `newName` set, rename the draft first then continue
        // the normal approve path (re-runs exact-name + high-similarity checks
        // against the new name).
        String newName = body.containsKey("newName") && body.get("newName") != null
                ? body.get("newName").toString().trim()
                : null;

        try {
            SkillDraftEntity result;
            if ("approve".equals(action)) {
                if (newName != null && !newName.isEmpty()) {
                    skillDraftService.renameDraft(id, newName, reviewedBy);
                }
                result = skillDraftService.approveDraft(id, reviewedBy, forceCreate);
            } else if ("discard".equals(action)) {
                result = skillDraftService.discardDraft(id, reviewedBy);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "action must be 'approve' or 'discard'"));
            }
            return ResponseEntity.ok(toMap(result));
        } catch (HighSimilarityRejectedException e) {
            // 409 Conflict — FE distinguishes this from generic 400; drives Modal.confirm.
            Map<String, Object> errBody = new LinkedHashMap<>();
            errBody.put("error", e.getMessage());
            errBody.put("code", "HIGH_SIMILARITY");
            errBody.put("similarity", e.getSimilarity());
            errBody.put("mergeCandidateId", e.getCandidateId());
            errBody.put("mergeCandidateName", e.getCandidateName());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errBody);
        } catch (SkillNameConflictException e) {
            // 409 Conflict — exact-name collision. SKILL-DASHBOARD-POLISH-V2 §H exposes
            // existingSkillId so the FE can offer "Update existing" (merge endpoint
            // below) alongside "Rename and create new" / "Reject draft".
            Map<String, Object> errBody = new LinkedHashMap<>();
            errBody.put("error", e.getMessage());
            errBody.put("code", "NAME_CONFLICT");
            errBody.put("existingSkillName", e.getExistingSkillName());
            errBody.put("existingSkillId", e.getExistingSkillId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errBody);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SKILL-DASHBOARD-POLISH-V2 §H — merge a draft into an existing user skill.
     * Triggered by the FE Modal's "Update existing" branch after a 409 NAME_CONFLICT
     * exposes {@code existingSkillId}. See {@link SkillDraftService#mergeIntoExistingSkill}.
     */
    @PostMapping("/skill-drafts/{id}/merge")
    public ResponseEntity<Map<String, Object>> mergeDraft(
            @PathVariable String id,
            @RequestParam("targetSkillId") Long targetSkillId,
            @RequestParam("reviewedBy") Long reviewedBy) {
        if (reviewedBy == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "reviewedBy is required"));
        }
        if (targetSkillId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetSkillId is required"));
        }
        try {
            SkillDraftEntity result = skillDraftService.mergeIntoExistingSkill(
                    id, targetSkillId, reviewedBy);
            return ResponseEntity.ok(toMap(result));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * SKILL-CREATOR-PHASE-1.6 F2 / Phase 1.2 (2026-05-19) — operator-driven
     * eval-gate trigger for an existing {@link SkillDraftEntity}.
     *
     * <p>Body schema (all fields except targetAgentId optional):
     * <pre>{@code
     * {
     *   "targetAgentId": 123,            // REQUIRED — agent the with_skill side runs on
     *   "scenarios": ["sc-id-a", ...],   // OPTIONAL — existing ephemeral EvalScenario ids;
     *                                    //   when omitted, the controller auto-builds
     *                                    //   ephemerals from the draft's sourceSessionId
     *                                    //   (single-session fallback per D13 default).
     *   "threshold": 0.05                // OPTIONAL — pass-rate delta threshold;
     *                                    //   ignored when omitted (server default 5pp).
     *                                    //   Reserved for Phase 1.7 slider UI.
     * }
     * }</pre>
     *
     * <p>Returns {@code 202 Accepted} with the dispatched run-id list when the
     * eval gate fires; {@code 400 Bad Request} for missing draft / targetAgentId
     * / no scenarios. The actual aggregate verdict lands asynchronously on
     * {@code draft.evaluationResultJson} via
     * {@code SkillCreatorEvalCoordinator.onSessionLoopFinished}.
     *
     * <p>D13 decision (Phase 1.6 ratify): operator-manual trigger only — no
     * auto-fire on draft creation. Dashboard {@code TriggerEvaluationModal}
     * (Phase 1.3 FE) hits this endpoint after the operator picks the target
     * agent + optional scenarios.
     */
    @PostMapping("/skill-drafts/{id}/evaluate")
    public ResponseEntity<?> triggerDraftEvaluation(
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "request body is required"));
        }
        if (skillCreatorService == null || sessionRepository == null
                || evalScenarioRepository == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "EVAL_GATE_NOT_WIRED",
                    "message", "Eval gate is not wired in this deployment "
                            + "(skill-creator-eval module not present)"));
        }
        Object agentIdRaw = request.get("targetAgentId");
        Long targetAgentId = parseLong(agentIdRaw);
        if (targetAgentId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_REQUEST",
                    "message", "targetAgentId is required"));
        }

        SkillDraftEntity draft = skillDraftRepository.findById(id).orElse(null);
        if (draft == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NOT_FOUND",
                    "message", "Skill draft not found: " + id));
        }

        // Stamp the target agent into the draft so the dispatch can resolve it
        // (mirrors what SkillService.uploadSkill does in entry 1).
        draft.setTargetAgentId(targetAgentId);

        // Resolve scenarios: caller-provided > auto-build from sourceSessionId.
        List<String> scenarioIds = pluckStringList(request, "scenarios");
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            scenarioIds = autoBuildScenariosFromDraftSource(draft, targetAgentId);
            if (scenarioIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "NO_SCENARIOS",
                        "message", "No scenarios provided and draft has no resolvable source "
                                + "session to auto-build from. Provide scenarios[] explicitly."));
            }
        }

        // Save the targetAgentId update + dispatch.
        skillDraftRepository.save(draft);
        try {
            List<String> runIds = skillCreatorService.dispatchEvaluation(null, id, scenarioIds);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("draftId", id);
            body.put("targetAgentId", targetAgentId);
            body.put("scenarioIds", scenarioIds);
            body.put("runIds", runIds);
            body.put("status", "evaluating");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        } catch (RuntimeException e) {
            log.warn("SkillDraftController.triggerDraftEvaluation: dispatch failed for draftId={}: {}",
                    id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "DISPATCH_FAILED",
                    "message", e.getMessage()));
        }
    }

    /**
     * Auto-build ephemeral scenarios from the draft's {@code sourceSessionId}
     * (single-session fallback). Returns the new scenario ids, or empty list
     * when the draft has no source session. Persisted in their own
     * transaction (saveAll) before the dispatch tx so the dispatcher's
     * scenario lookup succeeds.
     */
    private List<String> autoBuildScenariosFromDraftSource(SkillDraftEntity draft, Long targetAgentId) {
        String sourceSessionId = draft.getSourceSessionId();
        if (sourceSessionId == null || sourceSessionId.isBlank()) {
            return List.of();
        }
        SessionEntity source = sessionRepository.findById(sourceSessionId).orElse(null);
        if (source == null) return List.of();
        List<EvalScenarioEntity> scenarios = skillCreatorService
                .buildEphemeralScenariosFromSessions(List.of(source), targetAgentId);
        if (scenarios.isEmpty()) return List.of();
        evalScenarioRepository.saveAll(scenarios);
        return scenarios.stream().map(EvalScenarioEntity::getId).toList();
    }

    private static Long parseLong(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> pluckStringList(Map<String, Object> request, String key) {
        Object raw = request.get(key);
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = list.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .toList();
        return out.isEmpty() ? null : out;
    }

    // ───────────────────────────────────────────────────────────────────────
    // SKILL-DASHBOARD-POLISH-V2.5 — alias endpoints under /api/skills/drafts/*
    // FE migrated to the new path style (POST approve/reject + paged GET).
    // Old PATCH /api/skill-drafts/{id} is preserved for V1 callers.
    // ───────────────────────────────────────────────────────────────────────

    /** Paged listing with optional status filter. */
    @GetMapping("/skills/drafts")
    public ResponseEntity<Map<String, Object>> listDraftsPaged(
            @RequestParam(name = "userId", required = true) Long userId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        // Spring Data is 0-indexed; FE-friendly 1-based input.
        PageRequest pr = PageRequest.of(safePage - 1, safePageSize);
        Page<SkillDraftEntity> result = (status != null && !status.isBlank())
                ? skillDraftRepository.findByOwnerIdAndStatus(userId, status, pr)
                : skillDraftRepository.findByOwnerId(userId, pr);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent().stream().map(this::toMap).collect(Collectors.toList()));
        body.put("page", safePage);
        body.put("pageSize", safePageSize);
        body.put("total", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    /** Pending-count badge endpoint. */
    @GetMapping("/skills/drafts/count")
    public ResponseEntity<Map<String, Object>> draftsCount(
            @RequestParam(name = "userId", required = true) Long userId,
            @RequestParam(name = "status", defaultValue = "draft") String status) {
        long count = skillDraftRepository.countByOwnerIdAndStatus(userId, status);
        return ResponseEntity.ok(Map.of("count", count, "status", status));
    }

    /** POST alias for approve (delegates to PATCH reviewDraft with action='approve'). */
    @PostMapping("/skills/drafts/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveAlias(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>();
        req.put("action", "approve");
        return reviewDraft(id, req);
    }

    /** POST alias for reject (delegates to PATCH reviewDraft with action='discard'). */
    @PostMapping("/skills/drafts/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectAlias(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>();
        req.put("action", "discard");
        return reviewDraft(id, req);
    }

    private Map<String, Object> toMap(SkillDraftEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("sourceSessionId", entity.getSourceSessionId());
        map.put("ownerId", entity.getOwnerId());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("triggers", entity.getTriggers());
        map.put("requiredTools", entity.getRequiredTools());
        map.put("promptHint", entity.getPromptHint());
        map.put("extractionRationale", entity.getExtractionRationale());
        map.put("status", entity.getStatus());
        map.put("skillId", entity.getSkillId());
        map.put("createdAt", entity.getCreatedAt());
        map.put("reviewedAt", entity.getReviewedAt());
        map.put("reviewedBy", entity.getReviewedBy());
        // Plan r2 §9 + Code Judge r1 B-FE-3 — surface dedupe metadata to FE.
        map.put("similarity", entity.getSimilarity());
        map.put("mergeCandidateId", entity.getMergeCandidateId());
        map.put("mergeCandidateName", entity.getMergeCandidateName());
        // Phase 1.6 hotfix r6 (2026-05-19): surface V91 columns so FE
        // SkillDraftEvaluationReport tab can render. Phase 1.1 commit 91f5ed6
        // added these to t_skill_draft + SkillDraftEntity getters but forgot
        // to wire them through this toMap serializer → API response missed
        // evaluationResult entirely → FE EvaluationReport tab stayed empty
        // even after eval completed and wrote evaluation_result_json.
        map.put("targetAgentId", entity.getTargetAgentId());
        map.put("candidateSkillId", entity.getCandidateSkillId());
        map.put("source", entity.getSource());
        String evalJson = entity.getEvaluationResultJson();
        if (evalJson != null && !evalJson.isBlank()) {
            try {
                Map<String, Object> evalObj = SHARED_OBJECT_MAPPER.readValue(
                        evalJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                map.put("evaluationResult", evalObj);
                Object evaluatedAt = evalObj.get("evaluatedAt");
                if (evaluatedAt != null) map.put("evaluatedAt", evaluatedAt);
            } catch (Exception e) {
                log.warn("SkillDraft {} evaluation_result_json malformed, skipping: {}",
                        entity.getId(), e.getMessage());
            }
        }
        return map;
    }
}
