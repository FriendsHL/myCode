package com.skillforge.server.evolve;

import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.evolve.dto.EvolveRunSummaryDto;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C0) — on-demand trigger entry for the
 * agent-driven auto-evolving loop.
 *
 * <p>{@code POST /api/evolve/agents/{agentId}/run} creates a
 * {@link FlywheelRunEntity} with {@code loop_kind=evolve} for the target agent,
 * spawns a system session bound to the TOP-LEVEL {@code evolve-orchestrator}
 * agent (V131 seed), and kicks the orchestrator loop with
 * {@link ChatService#chatAsync}. The orchestrator then drives
 * report→candidate→A/B→gate→record itself (see its system prompt).
 *
 * <p>Mirrors {@code FlywheelController.runLoop} +
 * {@code AttributionDispatcherService.dispatchOne} (system session + chatAsync).
 * The orchestrator runs TOP-LEVEL (this C0 session) — NOT as a workflow
 * sub-agent — so its A/B fan-out via {@code TriggerAbEval} stays 2 layers deep
 * (no 3-layer recursion trap; architect R1).
 *
 * <p>Auth: V1 single-tenant dogfood pattern — goes through the same Bearer-token
 * AuthInterceptor on all {@code /api/**} routes (matches FlywheelController).
 */
@RestController
@RequestMapping("/api/evolve")
public class EvolveController {

    private static final Logger log = LoggerFactory.getLogger(EvolveController.class);

    /** SYSTEM user marker — mirrors {@code AttributionDispatcherService.SYSTEM_USER_ID}. */
    static final long SYSTEM_USER_ID = 0L;

    /** Default loop iteration ceiling threaded into the kickoff prompt. */
    static final int DEFAULT_MAX_ITER = 10;
    static final int MIN_MAX_ITER = 1;
    static final int MAX_MAX_ITER = 50;

    /**
     * OPT-REPORT-V1 reused window (the evolve run reuses the FlywheelRun time
     * window columns; the orchestrator itself scopes the report via the
     * opt-report workflow, so this is a defensive default for the row).
     */
    static final int DEFAULT_WINDOW_DAYS = 7;

    private final AgentRepository agentRepository;
    private final FlywheelRunService flywheelRunService;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final EvolveReadService evolveReadService;

    public EvolveController(AgentRepository agentRepository,
                            FlywheelRunService flywheelRunService,
                            SessionService sessionService,
                            ChatService chatService,
                            EvolveReadService evolveReadService) {
        this.agentRepository = agentRepository;
        this.flywheelRunService = flywheelRunService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.evolveReadService = evolveReadService;
    }

    /**
     * Trigger an auto-evolving run for the target agent.
     *
     * @param agentId  target agent (the agent being evolved); 404 if missing.
     * @param maxIter  optional iteration ceiling threaded into the orchestrator
     *                 prompt (clamped to [{@value #MIN_MAX_ITER},
     *                 {@value #MAX_MAX_ITER}], default {@value #DEFAULT_MAX_ITER}).
     *                 NOTE: this is a soft prompt hint — the server-side hard cap
     *                 is FR-C7's per-evolve-run A/B budget on TriggerAbEval.
     */
    @PostMapping("/agents/{agentId}/run")
    public ResponseEntity<?> run(@PathVariable("agentId") Long agentId,
                                 @RequestParam(value = "maxIter", required = false) Integer maxIterRaw) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        int maxIter = clampMaxIter(maxIterRaw);

        // Pre-check: the target agent (being evolved) exists. Like
        // FlywheelController, we don't gate on agent_type — operators may evolve
        // a system agent during dogfood.
        AgentEntity targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent not found: id=" + agentId));

        // The orchestrator agent is seeded by V131. A missing row indicates a
        // partially-rolled-out instance — fail fast with 503 rather than firing
        // chatAsync into a void (matches FlywheelController's system-agent guard).
        AgentEntity orchestratorAgent = agentRepository.findFirstByName(SystemAgentNames.EVOLVE_ORCHESTRATOR)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "evolve-orchestrator system agent not seeded; check V131 migration"));

        // HIGH-1 fix: per-agent in-flight guard. Reject with 409 if this agent
        // already has an active (running / pending) evolve run. Two concurrent
        // evolve loops on the same agent would race on candidates / A/B runs
        // (mirrors ImprovementConflictException guards in PromptImproverService:617
        // / SkillAbEvalService:210 that prevent concurrent A/B on the same agent).
        if (flywheelRunService.hasActiveEvolveRun(agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "agent " + agentId + " already has an active evolve run; "
                            + "wait for it to complete (or fail) before triggering another");
        }

        // STEP 1: create the evolve-run row (loop_kind=evolve) for the TARGET
        // agent. REUSE FlywheelRun — no new table. Each iteration becomes a
        // step_kind=evolve_iteration row (RecordIteration).
        Map<String, Object> inputJson = new LinkedHashMap<>();
        inputJson.put("targetAgentId", agentId);
        inputJson.put("maxIter", maxIter);
        inputJson.put("orchestratorAgentId", orchestratorAgent.getId());
        FlywheelRunEntity run = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_EVOLVE,
                FlywheelRunEntity.TRIGGER_SOURCE_API,
                inputJson,
                agentId,
                DEFAULT_WINDOW_DAYS);

        // STEP 2: spawn the orchestrator system session + kick the loop. The
        // kickoff prompt threads targetAgentId + evolveRunId (+ maxIter) — the
        // orchestrator's system prompt parses these.
        SessionEntity orchestratorSession =
                sessionService.createSession(SYSTEM_USER_ID, orchestratorAgent.getId());
        // attachGeneratorSession transitions the run pending→running and records
        // the orchestrator session id (mirrors OptReportService's pattern).
        flywheelRunService.attachGeneratorSession(run.getId(), orchestratorSession.getId());

        String kickoffPrompt = String.format(
                "targetAgentId=%d evolveRunId=%s maxIter=%d。请按你的系统提示驱动自动进化 loop："
                        + "先 RunWorkflow opt-report 拿归因 report，再对每个 issue 迭代"
                        + "（GenerateCandidate → TriggerAbEval（带 evolveRunId=%s）→ GetAbResult 轮询"
                        + " → 科学判断是否保留 → RecordIteration），最多 %d 轮，"
                        + "末尾汇总 kept 候选交人定夺，不要直接 promote。",
                agentId, run.getId(), maxIter, run.getId(), maxIter);
        chatService.chatAsync(orchestratorSession.getId(), kickoffPrompt, SYSTEM_USER_ID);

        log.info("[Evolve] evolve run started: evolveRunId={} targetAgentId={} orchestratorSessionId={} maxIter={}",
                run.getId(), agentId, orchestratorSession.getId(), maxIter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("evolveRunId", run.getId());
        body.put("sessionId", orchestratorSession.getId());
        body.put("agentId", agentId);
        body.put("agentName", targetAgent.getName());
        body.put("maxIter", maxIter);
        body.put("status", "running");
        // 202 ACCEPTED — the loop is async; the session is queued and the
        // orchestrator will drive it in the background (mirrors FlywheelController
        // .runLoop's accepted() pattern).
        return ResponseEntity.accepted().body(body);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Module D — read API (FR-D1 / FR-D3 / FR-D4)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * FR-D4 — list evolve runs for a given agent, newest-first.
     *
     * <p>Returns an {@code {items: [...]} } envelope (footgun #6b: FE must NOT
     * treat the response as a bare array).
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "items": [
     *     {
     *       "evolveRunId":    "...",
     *       "status":         "completed",
     *       "createdAt":      "2026-05-31T10:00:00Z",
     *       "updatedAt":      "2026-05-31T12:00:00Z",
     *       "iterationCount": 5,
     *       "finalDelta":     2.4
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @param agentId target agent id; 400 if &lt;= 0.
     * @param limit   max runs to return (default 20, clamped to [1, 100]).
     */
    @GetMapping("/agents/{agentId}/runs")
    public ResponseEntity<?> listRuns(
            @PathVariable("agentId") Long agentId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        if (agentId == null || agentId <= 0L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "agentId must be a positive long; got " + agentId);
        }
        List<EvolveRunSummaryDto> items = evolveReadService.listRunsForAgent(agentId, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /**
     * FR-D3 — full detail for a single evolve run, including all recorded
     * iterations (trajectory).
     *
     * <p>Returns a single JSON object (NOT enveloped). 404 if the run is not
     * found or its {@code loop_kind} is not {@code evolve}.
     *
     * <p>Response shape:
     * <pre>
     * {
     *   "evolveRunId":  "...",
     *   "agentId":      7,
     *   "agentName":    "my-agent",
     *   "status":       "completed",
     *   "createdAt":    "2026-05-31T10:00:00Z",
     *   "updatedAt":    "2026-05-31T12:30:00Z",
     *   "iterations": [
     *     {
     *       "iteration":      1,
     *       "surface":        "prompt",
     *       "changeDesc":     "Tightened the system prompt greeting.",
     *       "candidateId":    "cand-abc",
     *       "baselineScore":  72.5,
     *       "candidateScore": 74.9,
     *       "delta":          2.4,
     *       "kept":           true,
     *       "abRunId":        "ab-xyz",
     *       "createdAt":      "2026-05-31T10:05:00Z"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param evolveRunId the run UUID
     */
    @GetMapping("/runs/{evolveRunId}")
    public ResponseEntity<?> getRunDetail(
            @PathVariable("evolveRunId") String evolveRunId) {
        if (evolveRunId == null || evolveRunId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evolveRunId is required");
        }
        EvolveRunDetailDto detail = evolveReadService.getRunDetail(evolveRunId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Evolve run not found or not an evolve run: " + evolveRunId));
        return ResponseEntity.ok(detail);
    }

    private static int clampMaxIter(Integer raw) {
        if (raw == null) return DEFAULT_MAX_ITER;
        if (raw < MIN_MAX_ITER) return MIN_MAX_ITER;
        return Math.min(raw, MAX_MAX_ITER);
    }
}
