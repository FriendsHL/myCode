package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionPatternEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.SessionPatternRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService.SessionAnnotationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.2: hourly cron entry point that scans
 * {@code t_session_pattern} for candidate failure clusters, applies the four
 * ratify-locked filters (surface allowlist / member-count threshold / 24h
 * cooldown / no in-flight event), and dispatches the {@code attribution-curator}
 * agent for each survivor.
 *
 * <p>Spec: {@code docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §1}
 * (dispatcher flow) + {@code prd.md §用户流程 step 2} + ratify #2 (24h cooldown)
 * + ratify #6 (surface ∈ \{skill, prompt\}).
 *
 * <p>Per-pattern dispatch: creates a fresh system-owned session bound to the
 * {@code attribution-curator} agent and submits a per-pattern user-message
 * carrying the {@code patternId}. The agent then runs its 4-STEP system-prompt
 * pipeline (PatternRead → SessionAnnotationRead+GetTrace × member sessions →
 * LLM reasoning → ProposeOptimization). Cooldown is enforced by the
 * {@code ProposeOptimization} tool setting
 * {@code t_optimization_event.cooldown_expires_at = NOW() + 24h} at the end of
 * a successful run; the dispatcher reads that column to gate future scans.
 *
 * <p>Wiring note: this service exposes {@link #dispatchPendingPatterns(int)} as
 * a plain method. Phase 1.4 / Bootstrap can attach it to either a Spring
 * {@code @Scheduled} cron or to the V81 ScheduledTask
 * {@code attribution-dispatcher-hourly} via a dispatcher tool — Phase 1.2 only
 * delivers the service itself + the 4 agent-facing tools.
 *
 * <p>Concurrency: not {@code @Transactional}'d at the method level — each
 * sub-call (repository scan / per-pattern dispatch) uses its own short
 * transaction so a single pattern's failure doesn't block the rest. {@link
 * DataAccessException} is caught per-pattern (per V1 W2 + V2 W2 lessons:
 * narrow catch, never swallow generic {@code Exception}).
 */
@Service
public class AttributionDispatcherService {

    private static final Logger log = LoggerFactory.getLogger(AttributionDispatcherService.class);

    public static final String CURATOR_AGENT_NAME = "attribution-curator";
    public static final int DEFAULT_MAX_DISPATCH_PER_RUN = 5;
    public static final int MIN_MEMBER_COUNT = 3;
    /**
     * MULTI-DIM-ATTRIBUTION 2026-05-21: relaxed threshold for
     * {@code outcome=infrastructure_failure} patterns — aligns with
     * {@code SessionPatternClusterService.MIN_MEMBERS_INFRA_OUTCOME}. Without
     * this the cluster would admit a 2-session infra pattern but the
     * dispatcher would silently drop it at Filter 2 ("defensive duplicate of
     * V1 cluster rule"), defeating the per-outcome admission relaxation.
     */
    public static final int MIN_MEMBER_COUNT_INFRA = 2;
    public static final int SCAN_PAGE_SIZE = 100;
    /** SYSTEM user marker — same convention as V69 / V75 / V79 ScheduledTask creator_user_id. */
    public static final long SYSTEM_USER_ID = 0L;

    /**
     * Phase 1.4 — orphan sentinel TTL. The attribution-curator agent is
     * configured for ~minutes of LLM time; if a {@code dispatch_initiated}
     * row sits unchanged for more than this window the agent must have
     * crashed / been killed / never started. {@link #cleanupOrphanSentinels}
     * removes such rows so they don't permanently block future dispatcher
     * scans on the same pattern (Phase 1.3 code-reviewer MEDIUM).
     */
    public static final Duration ORPHAN_SENTINEL_TTL = Duration.ofHours(2);

    /**
     * Surfaces V3 auto-dispatches (ratify #6). {@code behavior_rule} is V4,
     * {@code other / unclear} are recorded but never approved.
     */
    static final Set<String> ELIGIBLE_SURFACES = Set.of(
            OptimizationEventEntity.SURFACE_SKILL,
            OptimizationEventEntity.SURFACE_PROMPT);

    /**
     * Pre-terminal event stages — having any of these for a pattern means an
     * earlier optimization is still in flight, so the dispatcher must not start
     * a competing curator run. Phase 1.3 added {@code dispatch_initiated} (the
     * sentinel row written before chatAsync — closes the race window where two
     * concurrent dispatcher ticks could both see "no event") and
     * {@code candidate_generating} (set by AttributionApprovalService.approve).
     *
     * <p>Phase 1.3 reviewer fix: also includes {@code candidate_failed}.
     * Per tech-design.md §6, {@code candidate_failed} is terminal until the
     * operator manually retries via the Phase 1.4 REST endpoint — auto-retrying
     * by re-dispatching would burn LLM budget on systematic failures (e.g. the
     * curator's proposal contradicts a hard constraint of the surface).
     * Other terminal stages ({@code proposal_rejected}, {@code ab_failed},
     * {@code rolled_back}, {@code verified}) are NOT in this set because
     * either (a) operator already explicitly closed the loop, or (b)
     * downstream Phase 1.4+ wiring will re-evaluate the pattern in the
     * normal scan after enough new evidence accumulates.
     */
    static final Set<String> ACTIVE_STAGES = Set.of(
            OptimizationEventEntity.STAGE_DISPATCH_INITIATED,
            OptimizationEventEntity.STAGE_PROPOSAL_PENDING,
            OptimizationEventEntity.STAGE_PROPOSAL_APPROVED,
            OptimizationEventEntity.STAGE_CANDIDATE_GENERATING,
            OptimizationEventEntity.STAGE_CANDIDATE_READY,
            OptimizationEventEntity.STAGE_CANDIDATE_FAILED,
            OptimizationEventEntity.STAGE_CANDIDATE_CREATED,
            OptimizationEventEntity.STAGE_AB_RUNNING,
            OptimizationEventEntity.STAGE_AB_PASSED,
            OptimizationEventEntity.STAGE_CANARY_STARTED);

    private final SessionPatternRepository patternRepository;
    private final OptimizationEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final Clock clock;
    private final AttributionEventBroadcaster broadcaster;

    public AttributionDispatcherService(SessionPatternRepository patternRepository,
                                        OptimizationEventRepository eventRepository,
                                        AgentRepository agentRepository,
                                        SessionService sessionService,
                                        ChatService chatService,
                                        Clock clock,
                                        AttributionEventBroadcaster broadcaster) {
        this.patternRepository = patternRepository;
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.clock = clock;
        this.broadcaster = broadcaster;
    }

    /**
     * Outcome counts of a single dispatcher run. Useful for log lines + future
     * dashboard surfaces; not persisted.
     *
     * @param scanned         total pattern rows seen this run
     * @param dispatched      patterns that passed all filters AND for which the
     *                        attribution-curator session was successfully spawned
     * @param skippedSurface  surface ∉ \{skill, prompt\} (ratify #6)
     * @param skippedCooldown latest event row's {@code cooldown_expires_at} > now
     * @param skippedActive   non-empty pre-terminal stage event for this pattern
     */
    public record DispatchResult(int scanned,
                                 int dispatched,
                                 int skippedSurface,
                                 int skippedCooldown,
                                 int skippedActive) {}

    /**
     * DISPATCHER-ORCHESTRATOR-REFACTOR — a single candidate row returned by
     * {@link #listAndReserveCandidates(int)} for the LLM-orchestrated dispatcher
     * agent to walk. Each entry has already passed the 4 ratify-locked filters
     * and has a freshly-written {@code dispatch_initiated} sentinel that the
     * LLM can transition by spawning attribution-curator via {@code SubAgent}.
     *
     * @param patternId        {@code t_session_pattern.id}
     * @param sentinelEventId  ID of the {@code dispatch_initiated} row reserved
     *                         for this candidate; the downstream curator will
     *                         UPDATE this row's {@code stage} (proposal_pending
     *                         / proposal_rejected) rather than insert a fresh
     *                         row
     * @param signature        pattern signature (informational for the LLM)
     * @param outcome          {@code t_session_pattern.outcome} (failure /
     *                         partial_success / cancelled /
     *                         infrastructure_failure / cost_high)
     * @param surface          {@code t_session_pattern.suspect_surface}
     * @param memberCount      number of sessions clustered under this pattern
     * @param lastSeenAt       most recent failure timestamp
     */
    public record CandidateEntry(long patternId,
                                 long sentinelEventId,
                                 String signature,
                                 String outcome,
                                 String surface,
                                 int memberCount,
                                 Instant lastSeenAt) {}

    /**
     * Result of {@link #listAndReserveCandidates(int)} — the raw candidate list
     * plus accounting counters for the LLM's STEP 4 summary line.
     *
     * @param candidates    eligible patterns with sentinels reserved (the LLM
     *                      walks this list and decides whether to dispatch per
     *                      entry); list size also equals
     *                      {@code reserved_count} in the wire payload
     * @param totalScanned  total pattern rows seen this run (including filtered)
     * @param filteredOut   patterns that did not survive the 4 filters
     *                      (skippedSurface + skippedCooldown + skippedActive +
     *                      under-threshold member count)
     */
    public record CandidateListResult(List<CandidateEntry> candidates,
                                      int totalScanned,
                                      int filteredOut) {
        /**
         * Defensive copy on construction so callers cannot mutate the backing
         * list shared with internal scan state (W2-java r2 fix per
         * code-reviewer / java-coding-style.md "Return defensive copies from
         * public APIs"). {@link List#copyOf} returns an unmodifiable copy.
         */
        public CandidateListResult {
            candidates = List.copyOf(candidates);
        }
    }

    /*
     * REMOVED 2026-05-20 — CRON-DUAL-SCHEDULE-FIX hotfix.
     *
     * Previously this method carried `@Scheduled(cron = "0 15 * * * *")`
     * AND `t_scheduled_task #6 attribution-dispatcher-hourly` also had cron
     * `0 15 * * * *` — TWO independent fire paths for the same logical
     * dispatch, controlled by completely separate state (annotation hardcoded
     * vs DB row `enabled` flag). Dashboard UI "disable schedule" only
     * affected t_scheduled_task; the @Scheduled annotation kept firing every
     * hour regardless, producing stale-pattern OptEvent #110-#115 noise +
     * wasting token over multiple days while user thought the cron was off.
     *
     * Fix: delete the Spring @Scheduled wrapper, making `t_scheduled_task` row
     * the single source of truth (UI control = real control). Future cron
     * fires now route through UserTaskScheduler → ScheduledTaskExecutor →
     * chatAsync(attribution-curator agent) when user re-enables task #6.
     * Trade-off: the agent-mediated path costs more tokens per dispatch
     * (LLM-driven) vs the direct method call, but is the existing canonical
     * path for all other system agent crons (session-annotator / metrics-
     * collector / memory-curator) so consistency wins. Document this in
     * AttributionDispatcherService class javadoc as the canonical entry point
     * for cron-driven dispatch; manual / e2e callers continue to use
     * AttributionEventController.triggerDispatch HTTP endpoint or directly
     * call dispatchPendingPatterns from tests.
     */

    /**
     * Scan candidate patterns and dispatch the curator agent for the top-N
     * eligible. {@code maxDispatchPerRun ≤ 0} falls back to
     * {@link #DEFAULT_MAX_DISPATCH_PER_RUN}.
     *
     * <p>DISPATCHER-ORCHESTRATOR-REFACTOR: legacy entry point retained for
     * backward compatibility with the REST endpoint
     * {@code POST /api/attribution/admin/trigger-dispatch} + the existing
     * unit/integration test surface. New cron path goes through the
     * {@code attribution-dispatcher} LLM agent + {@code ListAttributionCandidates}
     * + {@code SubAgent(action=dispatch, agentName=attribution-curator)} fan-out
     * instead. Internally this method now delegates the scan + filter + sentinel
     * write to {@link #listAndReserveCandidates(int)} and dispatches in a Java
     * loop, so both paths share one source of truth on filter semantics +
     * sentinel reservation.
     *
     * <p>Intentionally NOT {@code @Transactional} (Phase 1.2 reviewer fix):
     * sub-calls each carry their own short transaction. Wrapping the whole
     * scan would (a) make {@code chatAsync}'s pool worker race the outer
     * commit (silent "session not found" while we report dispatched++), and
     * (b) let one pattern's {@code DataAccessException} mark the outer tx
     * rollback-only, killing the entire batch on commit even though we
     * caught the exception and intended to continue.
     */
    public DispatchResult dispatchPendingPatterns(int maxDispatchPerRun) {
        int cap = maxDispatchPerRun > 0 ? maxDispatchPerRun : DEFAULT_MAX_DISPATCH_PER_RUN;

        Optional<AgentEntity> curatorOpt = agentRepository.findFirstByName(CURATOR_AGENT_NAME);
        if (curatorOpt.isEmpty()) {
            log.warn("[AttributionDispatcher] {} agent missing — V81 migration not applied? Skipping dispatch.",
                    CURATOR_AGENT_NAME);
            return new DispatchResult(0, 0, 0, 0, 0);
        }
        Long curatorAgentId = curatorOpt.get().getId();

        FilterScanOutcome scan = scanAndReserve(cap);

        int dispatched = 0;
        for (CandidateEntry candidate : scan.candidates()) {
            try {
                dispatchOne(candidate, curatorAgentId);
                dispatched++;
            } catch (DataAccessException dae) {
                // V1 W2 / V2 W2 lesson: narrow catch on persistence errors so
                // one pattern's DB hiccup doesn't poison the rest of the scan.
                log.error("[AttributionDispatcher] dispatch failed for pattern {} (DataAccessException): {}",
                        candidate.patternId(), dae.getMessage(), dae);
            } catch (RuntimeException e) {
                // Non-DB runtime failure (e.g. ChatService rejected execution,
                // pattern row mutated mid-flight). Log + continue.
                log.error("[AttributionDispatcher] dispatch failed for pattern {}: {}",
                        candidate.patternId(), e.getMessage(), e);
            }
        }

        log.info("[AttributionDispatcher] scan complete: scanned={} dispatched={} "
                        + "skippedSurface={} skippedCooldown={} skippedActive={} cap={}",
                scan.scanned(), dispatched, scan.skippedSurface(), scan.skippedCooldown(),
                scan.skippedActive(), cap);
        return new DispatchResult(scan.scanned(), dispatched, scan.skippedSurface(),
                scan.skippedCooldown(), scan.skippedActive());
    }

    /**
     * DISPATCHER-ORCHESTRATOR-REFACTOR — STEP 1 of the new LLM-orchestrated
     * dispatcher loop. Scans {@code t_session_pattern}, applies the 4
     * ratify-locked filters, writes a fresh {@code dispatch_initiated} sentinel
     * row for each survivor, and returns the candidates list for the LLM to
     * walk. The LLM then uses {@code SubAgent(action=dispatch,
     * agentName=attribution-curator, prompt=...)} per entry to start the
     * curator pipeline.
     *
     * <p>Sentinel-write isolation: a per-pattern {@code DataAccessException}
     * on the sentinel save is caught + logged + the pattern is silently
     * dropped from the returned list (matches the legacy
     * {@code dispatchPendingPatterns} per-pattern try/catch — one pattern's DB
     * hiccup must not abort the rest of the scan).
     *
     * <p>The returned {@link CandidateEntry#sentinelEventId()} is the ID of
     * the freshly written sentinel; downstream callers must reference that ID
     * when transitioning the row (e.g. attribution-curator's
     * {@code ProposeOptimization} / {@code WriteOptimizationEvent} STEP 4
     * UPDATEs the same row rather than inserting a duplicate). Sentinels for
     * candidates the LLM never routes are swept by
     * {@link #cleanupOrphanSentinels()}.
     *
     * @param max maximum candidates to return; values ≤0 fall back to
     *            {@link #DEFAULT_MAX_DISPATCH_PER_RUN}
     */
    public CandidateListResult listAndReserveCandidates(int max) {
        int cap = max > 0 ? max : DEFAULT_MAX_DISPATCH_PER_RUN;
        FilterScanOutcome scan = scanAndReserve(cap);
        int filteredOut = scan.skippedSurface() + scan.skippedCooldown() + scan.skippedActive()
                + scan.skippedMemberCount();
        return new CandidateListResult(scan.candidates(), scan.scanned(), filteredOut);
    }

    /**
     * Internal scan + filter + sentinel-reserve helper shared between
     * {@link #dispatchPendingPatterns(int)} (legacy Java fan-out) and
     * {@link #listAndReserveCandidates(int)} (new LLM-orchestrated path). One
     * source of truth for filter semantics + sentinel write ordering.
     */
    private FilterScanOutcome scanAndReserve(int cap) {
        // Wide scan: filter null on all dims so we see every pattern. Page size 100
        // is a defensive ceiling — V1 dogfood produces <20 distinct patterns per
        // recompute cycle in current data. SCAN_PAGE_SIZE bump is a one-line
        // change if observed pattern count rises.
        List<SessionPatternEntity> patterns = patternRepository.findWithFilters(
                null, null, null, PageRequest.of(0, SCAN_PAGE_SIZE));

        int scanned = patterns.size();
        int skippedSurface = 0;
        int skippedCooldown = 0;
        int skippedActive = 0;
        int skippedMemberCount = 0;
        List<CandidateEntry> reserved = new ArrayList<>();
        Instant now = clock.instant();

        for (SessionPatternEntity p : patterns) {
            if (reserved.size() >= cap) {
                break;
            }

            // Filter 1: surface allowlist (ratify #6).
            // MULTI-DIM-ATTRIBUTION 2026-05-21: infrastructure_failure outcomes
            // bypass the surface allowlist because they're inherently
            // surface-less (no skill / prompt was even reached — the agent
            // crashed at platform layer). The curator's STEP 1 will
            // fast-reject via WriteOptimizationEvent(proposal_rejected)
            // because there's no actionable optimization in V3 scope, but
            // reservation still has to happen so the rejection lands on the
            // timeline (operator-visible signal that "we saw this pattern
            // and chose not to act").
            boolean isInfraFailure = SessionAnnotationConstants.OUTCOME_INFRASTRUCTURE_FAILURE
                    .equals(p.getOutcome());
            if (!isInfraFailure && !ELIGIBLE_SURFACES.contains(p.getSuspectSurface())) {
                skippedSurface++;
                continue;
            }
            // Filter 2: member count threshold (defensive duplicate of V1
            // cluster recompute rule — clusters with <3 members are rarely
            // signal; skip without counting in a specific bucket).
            // MULTI-DIM-ATTRIBUTION 2026-05-21: infrastructure_failure uses
            // the relaxed MIN_MEMBER_COUNT_INFRA=2 to stay aligned with
            // SessionPatternClusterService.MIN_MEMBERS_INFRA_OUTCOME.
            int minMembers = isInfraFailure ? MIN_MEMBER_COUNT_INFRA : MIN_MEMBER_COUNT;
            if (p.getMemberCount() < minMembers) {
                skippedMemberCount++;
                continue;
            }
            // Filter 3: 24h cooldown (ratify #2). Any event row for this
            // pattern whose cooldown_expires_at is still in the future blocks
            // reservation.
            List<OptimizationEventEntity> activeCool = eventRepository
                    .findByPatternIdAndCooldownExpiresAtAfter(p.getId(), now);
            if (!activeCool.isEmpty()) {
                skippedCooldown++;
                continue;
            }
            // Filter 4: pre-terminal event already in flight (defensive — the
            // 24h cooldown row should catch this in practice, but if cooldown
            // expired while the prior event is mid-pipeline, we still skip).
            // Single COUNT(...) > 0 query (Phase 1.2 reviewer fix — collapsed
            // a 6-stage for-loop that was N+1 against SCAN_PAGE_SIZE).
            if (eventRepository.existsByPatternIdAndStageIn(p.getId(), ACTIVE_STAGES)) {
                skippedActive++;
                continue;
            }

            // Survive all 4 filters → reserve sentinel. Per-pattern narrow
            // catch (V1 W2 / V2 W2 + legacy dispatchPendingPatterns lesson):
            // one pattern's DataAccessException on sentinel write must not
            // poison the rest of the scan. Pattern is dropped from the
            // returned list — caller sees `reserved_count < survived_filters`
            // and can correlate via log lines.
            try {
                CandidateEntry entry = reserveSentinel(p);
                reserved.add(entry);
            } catch (DataAccessException dae) {
                log.error("[AttributionDispatcher] sentinel reserve failed for pattern {} "
                        + "(DataAccessException): {}", p.getId(), dae.getMessage(), dae);
            } catch (RuntimeException e) {
                log.error("[AttributionDispatcher] sentinel reserve failed for pattern {}: {}",
                        p.getId(), e.getMessage(), e);
            }
        }

        return new FilterScanOutcome(scanned, reserved, skippedSurface, skippedCooldown,
                skippedActive, skippedMemberCount);
    }

    /**
     * Internal record for the scan helper's return value. Not part of the
     * public API surface — callers use either {@link DispatchResult} (legacy
     * fan-out) or {@link CandidateListResult} (new LLM-orchestrated path).
     */
    private record FilterScanOutcome(int scanned,
                                     List<CandidateEntry> candidates,
                                     int skippedSurface,
                                     int skippedCooldown,
                                     int skippedActive,
                                     int skippedMemberCount) {}

    /**
     * Write the dispatch_initiated sentinel row for an eligible pattern and
     * broadcast the stage transition. Extracted from {@link #dispatchOne} so
     * both the legacy fan-out and the new LLM-orchestrated path share one
     * sentinel-write code path.
     */
    private CandidateEntry reserveSentinel(SessionPatternEntity pattern) {
        OptimizationEventEntity sentinel = new OptimizationEventEntity();
        sentinel.setPatternId(pattern.getId());
        // pattern.agentId may be null on legacy V1 rows pre-V75; substitute the
        // pattern's id-bearing fallback rather than violate NOT NULL — kept as
        // pattern.agentId only because at reservation time we no longer
        // have a "curator agent id" context (this path is shared with
        // listAndReserveCandidates where the LLM decides routing). On legacy
        // rows where agentId is null we leave the column null at this stage —
        // the curator's WriteOptimizationEvent UPDATE can backfill later from
        // its own session context. (Field is currently NOT NULL in the
        // schema; substitute 0L as a sentinel placeholder consistent with
        // SYSTEM_USER_ID convention to avoid the NOT NULL violation.)
        sentinel.setAgentId(pattern.getAgentId() != null ? pattern.getAgentId() : SYSTEM_USER_ID);
        sentinel.setSurfaceType(pattern.getSuspectSurface());
        sentinel.setStage(OptimizationEventEntity.STAGE_DISPATCH_INITIATED);
        // All other fields (changeType / description / expectedImpact / confidence
        // / risk / cooldownExpiresAt / candidate*Id / abRunId / canaryId /
        // attributionSessionId) intentionally left null — ProposeOptimizationTool
        // populates them when the curator finishes. createdAt / updatedAt are
        // auto-populated by @PrePersist.
        OptimizationEventEntity persistedSentinel = eventRepository.save(sentinel);
        // Phase 1.4: WS notify dashboard the moment a curator run kicks off
        // (previousStage=null because the event row is brand-new). broadcaster
        // is null-safe for unit tests without a Spring context.
        // Note: dispatcher itself is NOT @Transactional (Phase 1.2 reviewer
        // fix), so this broadcast does not have the in-tx phantom risk that
        // AttributionApprovalService's broadcasts carry — see ApprovalService
        // class javadoc for the in-tx trade-off discussion.
        if (broadcaster != null) {
            broadcaster.broadcastStageTransition(persistedSentinel, null);
        }
        log.debug("[AttributionDispatcher] sentinel written for patternId={} eventId={}",
                pattern.getId(), persistedSentinel.getId());

        return new CandidateEntry(
                pattern.getId(),
                persistedSentinel.getId(),
                pattern.getSignature(),
                pattern.getOutcome(),
                pattern.getSuspectSurface(),
                pattern.getMemberCount(),
                pattern.getLastSeenAt());
    }

    /**
     * Spawn a fresh attribution-curator session and submit the per-pattern
     * user-message that the system prompt expects. The agent's first tool call
     * (PatternRead) consumes the patternId; the rest of its 4-STEP pipeline
     * follows from there.
     *
     * <p>DISPATCHER-ORCHESTRATOR-REFACTOR: the sentinel write moved upstream
     * into {@link #reserveSentinel(SessionPatternEntity)} so both the legacy
     * fan-out ({@link #dispatchPendingPatterns(int)}) and the new
     * LLM-orchestrated path ({@link #listAndReserveCandidates(int)}) write
     * sentinels via one code path. By the time {@code dispatchOne} runs the
     * sentinel row already exists with id =
     * {@link CandidateEntry#sentinelEventId()} and stage
     * {@link OptimizationEventEntity#STAGE_DISPATCH_INITIATED}; the curator
     * will later UPDATE that same row rather than insert a fresh one.
     *
     * <p>If {@code createSession} / {@code chatAsync} fails, the sentinel row
     * stays behind in {@code dispatch_initiated} state until
     * {@link #cleanupOrphanSentinels()} sweeps it 2h later — same fault-recovery
     * shape the LLM-orchestrated path uses when the agent decides to skip a
     * candidate.
     */
    void dispatchOne(CandidateEntry candidate, Long curatorAgentId) {
        SessionEntity session = sessionService.createSession(SYSTEM_USER_ID, curatorAgentId);
        String prompt = composeDispatchPrompt(candidate);
        chatService.chatAsync(session.getId(), prompt, SYSTEM_USER_ID);
        log.info("[AttributionDispatcher] dispatched curator for patternId={} sessionId={} sentinelEventId={}",
                candidate.patternId(), session.getId(), candidate.sentinelEventId());
    }

    /**
     * Phase 1.4 — orphan sentinel TTL cleanup (Phase 1.3 reviewer MEDIUM fix).
     *
     * <p>Multi-sentinel races / agent crashes can leave {@code dispatch_initiated}
     * rows that never transition into {@code proposal_pending}. Without cleanup
     * those rows would permanently match Filter 4 ({@code ACTIVE_STAGES}
     * contains {@code dispatch_initiated}) and block all future dispatcher
     * runs on the same pattern. We DELETE rows whose
     * {@code stage='dispatch_initiated'} AND {@code createdAt < NOW() - 2h}
     * (longer than any reasonable curator run).
     *
     * <p>Cron {@code 0 50 * * * *} = every hour at :50. Intentionally offset
     * from V81 hourly dispatcher (':15'), V79 metrics-collector (':30'), V75
     * session-annotator (':00') so the four flywheel jobs don't collide on
     * top-of-hour spikes.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)} per Phase 1.2 reviewer fix
     * lesson: never JOIN whatever (if any) outer transaction the cron runner
     * carries. The cleanup is independent of any other dispatcher work.
     */
    @Scheduled(cron = "0 50 * * * *")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupOrphanSentinels() {
        Instant cutoff = clock.instant().minus(ORPHAN_SENTINEL_TTL);
        // Pull candidate rows then delete in a batch. Volume is bounded by
        // SCAN_PAGE_SIZE × hourly cadence, so the find-then-delete is fine
        // (and a single DELETE-by-condition is awkward in derived JPQL syntax).
        List<OptimizationEventEntity> sentinels = eventRepository.findByStageAndCreatedAtBefore(
                OptimizationEventEntity.STAGE_DISPATCH_INITIATED, cutoff);
        if (sentinels.isEmpty()) {
            log.debug("[AttributionDispatcher.cleanupOrphanSentinels] no orphan sentinels older than {}", cutoff);
            return;
        }
        eventRepository.deleteAll(sentinels);
        // BUG-2 (V3 e2e dogfood 2026-05-15): count > 0 is a soft alarm — these
        // sentinels mean curator AgentLoop ran but didn't advance to
        // proposal_pending / proposal_rejected. Common causes: (a) BUG-1 reject
        // path before fix, (b) LLM API failure mid-run, (c) curator-side
        // tool_use validation_error not retried. Operator sees this WARN and
        // can correlate with curator session logs by patternId.
        StringBuilder patternIds = new StringBuilder();
        for (int i = 0; i < sentinels.size(); i++) {
            if (i > 0) patternIds.append(",");
            patternIds.append(sentinels.get(i).getPatternId());
        }
        log.warn("[AttributionDispatcher.cleanupOrphanSentinels] deleted {} orphan sentinel(s) "
                + "older than TTL={} — curator AgentLoop did not advance these patternIds: [{}]. "
                + "Check curator session logs for tool_use validation_errors / LLM API failures.",
                sentinels.size(), ORPHAN_SENTINEL_TTL, patternIds);
    }

    /**
     * Compose the dispatch prompt for the attribution-curator. Package-private
     * so tests can pin the wire format without going through chatAsync.
     *
     * <p>MULTI-DIM-ATTRIBUTION 2026-05-21: includes {@code outcome=...} so the
     * curator can fast-reject {@code infrastructure_failure} at STEP 1 before
     * burning STEP 2 trace-drill budget. {@code outcome} is harmless for legacy
     * outcomes (failure / partial_success / cancelled) since the curator
     * already infers them from {@code PatternRead}'s response — having it on
     * the wire just shortens the path.
     *
     * <p>DISPATCHER-ORCHESTRATOR-REFACTOR: now takes {@link CandidateEntry}
     * (the sentinel-reserved candidate, includes {@code sentinelEventId}) so
     * the curator can reference the same row to UPDATE rather than INSERT a
     * fresh stage transition. The {@code agentId=...} segment is dropped
     * because the LLM-orchestrated path no longer carries agent identity on
     * the wire (the curator has its own LLM context); failure / partial /
     * cancelled outcomes never used it either.
     */
    static String composeDispatchPrompt(CandidateEntry candidate) {
        return String.format(
                "Run the 4-STEP attribution pipeline for patternId=%d "
                        + "(outcome=%s, suspectSurface=%s, memberCount=%d, sentinelEventId=%d). "
                        + "STEP 1: PatternRead(%d); STEP 2-4 follow your system prompt.",
                candidate.patternId(),
                candidate.outcome() == null ? "null" : candidate.outcome(),
                candidate.surface(),
                candidate.memberCount(),
                candidate.sentinelEventId(),
                candidate.patternId());
    }
}
