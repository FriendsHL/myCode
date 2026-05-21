package com.skillforge.server.sessionannotation;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.TraceScenarioImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.2): signal-stage annotation pipeline.
 *
 * <p>For each production top-level session that finished inside the window:
 * <ol>
 *   <li>load its traces + spans (origin='production' already enforced at the
 *       session row by upstream invariants — child traces inherit)</li>
 *   <li>call {@link TraceScenarioImportService#detectReasons} for the same
 *       6-reason output the SmartImport UI uses</li>
 *   <li>persist one {@code t_session_annotation} row per reason (UNIQUE on
 *       {@code (session_id, annotation_type, annotation_value, source)}
 *       backs idempotent re-runs)</li>
 * </ol>
 *
 * <p>Boundary: this service intentionally does <b>no LLM judgment</b>. Sessions
 * that earn a signal reason are surfaced via {@link #findSessionsNeedingLlmAnnotation(int)}
 * for the session-annotator agent's STEP 2 (AnnotateSessionTool, Phase 1.3) to
 * handle with LLM-derived {@code outcome} + {@code suspect_surface}.
 *
 * <p>Idempotency: the {@code uq_session_annotation} UNIQUE constraint (see
 * {@link SessionAnnotationEntity} {@code @Table} declaration) prevents duplicate
 * rows on re-run; we go through
 * {@link SessionAnnotationRepository#upsertSkipDuplicate} which uses Postgres'
 * native {@code ON CONFLICT DO NOTHING RETURNING id} — conflicts return
 * {@code null} and are silently skipped without aborting the surrounding
 * transaction (V1 W2 fix: the prior {@code saveAndFlush + catch DIVE} loop
 * silently lost subsequent rows once PG marked the tx aborted). This keeps
 * the hourly cron safe even if it re-runs a window that already produced
 * annotations (e.g. after a crash / restart).
 *
 * <p>Window semantics: {@code window} is interpreted as "sessions completed
 * within the last {@code window} duration". Default 1h matches the V75 hourly
 * cron. The window is intentionally a sliding overlap with whatever the prior
 * run covered — UNIQUE dedupe makes that safe.
 */
@Service
public class SessionAnnotationSignalService {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotationSignalService.class);

    /**
     * Same default as {@link TraceScenarioImportService} (line 35) — when the
     * caller doesn't override {@code minTokens}, anything ≥ 2000 tokens trips
     * the {@code high_token} reason. Kept private so a single value source
     * (this constant) governs the V1 pipeline; configurability lives at the
     * Tool input level, not yaml, to keep the config surface minimal per
     * Phase 1.2 brief.
     */
    private static final int DEFAULT_MIN_TOKENS = 2000;

    /** Tool returns at most this many "needs LLM" sessions per detect call — matches §4.1 STEP 2 cap. */
    static final int DEFAULT_LLM_QUEUE_LIMIT = 10;

    private final SessionRepository sessionRepository;
    private final LlmTraceRepository llmTraceRepository;
    private final LlmSpanRepository llmSpanRepository;
    private final SessionAnnotationRepository sessionAnnotationRepository;

    public SessionAnnotationSignalService(SessionRepository sessionRepository,
                                          LlmTraceRepository llmTraceRepository,
                                          LlmSpanRepository llmSpanRepository,
                                          SessionAnnotationRepository sessionAnnotationRepository) {
        this.sessionRepository = sessionRepository;
        this.llmTraceRepository = llmTraceRepository;
        this.llmSpanRepository = llmSpanRepository;
        this.sessionAnnotationRepository = sessionAnnotationRepository;
    }

    /**
     * Detect signal reasons for production sessions completed within {@code window}
     * and persist one annotation row per (session × reason) into
     * {@code t_session_annotation}.
     *
     * <p>Backward-compat 1-arg form — equivalent to
     * {@code detectAndPersist(window, null)}. Cron-driven callers (hourly task
     * #5 session-annotator + the legacy {@code DetectSignalAnnotationsTool}
     * without {@code agentId}) get the original cross-agent behavior.
     *
     * @param window look-back window (e.g. {@code Duration.ofHours(1)}).
     *               Must be positive; non-positive values throw IllegalArgumentException
     *               to avoid silently scanning the entire history.
     * @return total annotation rows written (sum across all sessions × reasons).
     *         A row that conflicts on the UNIQUE constraint counts as 0 (already
     *         existed from prior run).
     */
    @Transactional
    public int detectAndPersist(Duration window) {
        return detectAndPersist(window, null);
    }

    /**
     * FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21) — 2-arg overload with scope filter.
     *
     * <p>{@code agentIdFilter=null} → original cron behavior (every production
     * agent's session is in scope). {@code agentIdFilter} non-null → restricts
     * the scan to a single agent for the on-demand
     * {@code POST /api/flywheel/agents/{id}/run-loop} dispatch path. Same
     * idempotency guarantee as the 1-arg form (UNIQUE constraint on
     * {@code (session_id, annotation_type, annotation_value, source)}).
     *
     * @param window         look-back window (positive Duration).
     * @param agentIdFilter  optional agent id to restrict the scan; {@code null}
     *                       = scan all production agents (cron behavior).
     */
    @Transactional
    public int detectAndPersist(Duration window, Long agentIdFilter) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive Duration; got " + window);
        }
        Instant since = Instant.now().minus(window);
        // V5 EVAL-DYNAMIC-USER-SIM Phase 1.3: filter origin=production strictly.
        // This call already excludes 'eval' (EvalOrchestrator) and 'user_sim'
        // (UserSimulatorAgent trials) — defense-in-depth holds because the JPQL
        // matches origin equality, not inequality. Acts as the upstream gate for
        // downstream PatternClusterService + CanaryMetricsService isolation
        // (they consume rows we write here).
        List<SessionEntity> sessions = sessionRepository.findCompletedByOriginSince(
                SessionEntity.ORIGIN_PRODUCTION, since, agentIdFilter);
        if (sessions.isEmpty()) {
            log.info("[signal] no production sessions completed in window {} since {} agentIdFilter={}",
                    window, since, agentIdFilter);
            return 0;
        }

        int totalWritten = 0;
        for (SessionEntity session : sessions) {
            try {
                totalWritten += annotateOne(session);
            } catch (Exception e) {
                // Per-session isolation: a single bad session shouldn't fail the whole batch.
                // Logged at WARN so the hourly cron op surface can see it, but the cron keeps
                // running (matches §4.1 CONSTRAINT "If a tool returns an error, log it and
                // proceed; never abort the pipeline").
                log.warn("[signal] sessionId={} annotation failed: {}", session.getId(), e.getMessage(), e);
            }
        }
        log.info("[signal] window={} since={} agentIdFilter={} sessions={} annotationsWritten={}",
                window, since, agentIdFilter, sessions.size(), totalWritten);
        return totalWritten;
    }

    /**
     * Returns up to {@code limit} sessions that earned ≥ 1 signal annotation and
     * have NOT yet been annotated by the LLM pass (no {@code source='llm'} row).
     * Used by {@link com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool}
     * to forward the queue to STEP 2 of the agent pipeline.
     *
     * <h4>SYSTEM-AGENT-TYPING F7 Phase 1.2 — user-agent priority</h4>
     *
     * <p>Pre-Phase-1.2 behavior queried {@code findRecentByLimit("signal", cap*3)}
     * uniformly by recency, then took the first {@code cap=10} grouped sessions.
     * In production this starved <b>every</b> user-agent session because the
     * hourly cron system agents (attribution-curator + session-annotator +
     * metrics-collector) flood the most-recent signal stream — see prd.md F7
     * for the 2026-05-17 SQL audit (117 outcome annotations all on system
     * agents, 0 on user agents over 110 user-agent sessions).
     *
     * <p>Phase 1.2 fix uses a 3-tier candidate strategy:
     * <ol>
     *   <li><b>User-agent first</b>: {@code findRecentBySourceAndAgentType("user", cap*3)}
     *       so user agents always own the front of the queue when available</li>
     *   <li><b>System-agent backfill</b>: only if the user bucket didn't fill the
     *       cap, query {@code findRecentBySourceAndAgentType("system", cap*3)}</li>
     *   <li><b>Catch-all fallback</b>: if still under cap, query
     *       {@code findRecentByLimit("signal", cap*3)} to surface orphan signals
     *       whose session has {@code agent_id=NULL} or whose agent row was
     *       deleted (preserves pre-Phase-1.2 behavior for pathological data)</li>
     * </ol>
     *
     * <p>Each tier appends to the same {@link LinkedHashMap} keyed by sessionId, so
     * user-agent sessions appear earlier in iteration order and are never
     * displaced by the take-first-{@code cap} truncation at the end. Sessions
     * already LLM-annotated are filtered identically to pre-Phase-1.2.
     *
     * <p>Does <b>not</b> touch {@code SessionAnnotationLlmService.DECISION HEURISTICS} —
     * the {@code outcome} / {@code suspect_surface} enums and confidence semantics
     * are preserved as required by F7's "不改 DECISION HEURISTICS" constraint.
     */
    @Transactional(readOnly = true)
    public List<SessionNeedingLlmDto> findSessionsNeedingLlmAnnotation(int limit) {
        return findSessionsNeedingLlmAnnotation(limit, null);
    }

    /**
     * FLYWHEEL-PER-AGENT-RUN-NOW r2 fix (2026-05-21): overload accepting an
     * optional {@code agentIdFilter}. When non-null, the 3-tier prioritisation
     * (user → system → orphan fallback) is bypassed and we scan ONLY rows for
     * the specified agent — prevents the on-demand per-agent loop from
     * silently annotating other agents' pending sessions (code-reviewer r1
     * W2: scope leak).
     *
     * <p>When {@code agentIdFilter == null} (cron path) the original 3-tier
     * behaviour is preserved byte-identically.
     */
    @Transactional(readOnly = true)
    public List<SessionNeedingLlmDto> findSessionsNeedingLlmAnnotation(int limit, Long agentIdFilter) {
        int capped = Math.max(1, Math.min(limit, DEFAULT_LLM_QUEUE_LIMIT));
        int overFetch = capped * 3;

        // Preserve insertion order = priority order (user → system → orphan).
        LinkedHashMap<String, List<String>> reasonsBySession = new LinkedHashMap<>();

        if (agentIdFilter != null) {
            // PER-AGENT SCOPE: skip 3-tier prioritisation. Scope-leak guard:
            // only enqueue sessions belonging to the requested agent so a
            // dashboard "Run loop on agent X" trigger never annotates other
            // agents' pending sessions.
            accumulateReasons(reasonsBySession,
                    sessionAnnotationRepository.findRecentBySourceAndAgentId(
                            SessionAnnotationEntity.SOURCE_SIGNAL, agentIdFilter, overFetch));
            removeAlreadyLlmAnnotated(reasonsBySession);
            return materialiseQueue(reasonsBySession, capped);
        }

        // Tier 1: user-agent signals (F7 priority).
        accumulateReasons(reasonsBySession,
                sessionAnnotationRepository.findRecentBySourceAndAgentType(
                        SessionAnnotationEntity.SOURCE_SIGNAL, "user", overFetch));
        // Phase 2 W1 fix: dedup LLM-annotated sessions before the tier-2 guard reads
        // size. Without this, a degenerate scenario (e.g. Tier 1 returns exactly
        // {@code capped} user sessions that all already have source='llm') would
        // skip Tier 2/3 and the final post-tier dedup would leave an empty queue —
        // hiding system-agent signals the agent could otherwise annotate.
        removeAlreadyLlmAnnotated(reasonsBySession);

        // Tier 2: system-agent backfill if the user bucket didn't fill the cap.
        if (reasonsBySession.size() < capped) {
            accumulateReasons(reasonsBySession,
                    sessionAnnotationRepository.findRecentBySourceAndAgentType(
                            SessionAnnotationEntity.SOURCE_SIGNAL, "system", overFetch));
            removeAlreadyLlmAnnotated(reasonsBySession);
        }

        // Tier 3: catch-all fallback for orphan signal rows (session.agent_id=NULL,
        // or t_agent row deleted) — preserves pre-Phase-1.2 surface so we don't
        // regress on pathological data. The over-fetch limit applies again here.
        if (reasonsBySession.size() < capped) {
            accumulateReasons(reasonsBySession,
                    sessionAnnotationRepository.findRecentByLimit(
                            SessionAnnotationEntity.SOURCE_SIGNAL, overFetch));
            removeAlreadyLlmAnnotated(reasonsBySession);
        }

        return materialiseQueue(reasonsBySession, capped);
    }

    /**
     * Extract the post-tier "load session names + cap to size" tail logic into
     * a helper so the per-agent overload (FLYWHEEL-PER-AGENT-RUN-NOW r2 fix)
     * can reuse it after its single-tier scan.
     */
    private List<SessionNeedingLlmDto> materialiseQueue(LinkedHashMap<String, List<String>> reasonsBySession,
                                                         int capped) {
        if (reasonsBySession.isEmpty()) {
            return Collections.emptyList();
        }

        // Load minimal session fields to fill the DTO (agentName needed by the agent prompt).
        List<String> remaining = new ArrayList<>(reasonsBySession.keySet());
        Map<String, SessionEntity> byId = sessionRepository.findAllById(remaining).stream()
                .collect(Collectors.toMap(SessionEntity::getId, s -> s, (a, b) -> a));

        List<SessionNeedingLlmDto> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : reasonsBySession.entrySet()) {
            if (out.size() >= capped) break;
            SessionEntity s = byId.get(e.getKey());
            String agentName = (s != null && s.getAgentId() != null)
                    ? "agent#" + s.getAgentId()  // V1: we only have agentId in SessionEntity, not name
                    : null;
            out.add(new SessionNeedingLlmDto(e.getKey(), agentName, List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Accumulator helper that adds annotation reasons to the running map, grouped
     * by {@code sessionId}. Preserves insertion order (the caller relies on this
     * to keep priority tiers — user before system before orphan).
     */
    private static void accumulateReasons(LinkedHashMap<String, List<String>> map,
                                          List<SessionAnnotationEntity> rows) {
        for (SessionAnnotationEntity row : rows) {
            map.computeIfAbsent(row.getSessionId(), k -> new ArrayList<>())
                    .add(row.getAnnotationType());
        }
    }

    /**
     * Phase 2 W1 fix: filter out sessions that already have a {@code source='llm'}
     * annotation row, in place. Called after each tier so the per-tier guard
     * ({@code size() < capped}) measures the post-dedup count — otherwise a tier
     * fully populated with already-LLM-annotated sessions silently skips
     * subsequent tiers and produces an empty queue.
     *
     * <p>Small-N LLM-filter query (~10s of sessions per cap=10 invocation × 3 tiers
     * worst case). The repeated calls re-query overlapping session lists — the
     * overhead is sub-millisecond at V1 dogfood volumes, and avoiding it would
     * require threading a "remaining slots" counter through accumulateReasons
     * which obscures the tier logic.
     */
    private void removeAlreadyLlmAnnotated(LinkedHashMap<String, List<String>> map) {
        if (map.isEmpty()) return;
        List<String> sessionIds = new ArrayList<>(map.keySet());
        List<String> withLlm = sessionAnnotationRepository
                .findSessionIdsWithSource(sessionIds, SessionAnnotationEntity.SOURCE_LLM);
        if (!withLlm.isEmpty()) {
            map.keySet().removeAll(withLlm);
        }
    }

    /**
     * Inner helper — annotate one session. Returns the number of rows newly written
     * (UNIQUE conflicts count as 0).
     */
    private int annotateOne(SessionEntity session) {
        String sessionId = session.getId();
        // All traces for this session (root + child). detectReasons uses the primary
        // (root) trace's status + the aggregate of all spans.
        List<LlmTraceEntity> traces = llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc(
                sessionId, SessionEntity.ORIGIN_PRODUCTION);
        if (traces.isEmpty()) {
            // MULTI-DIM-ATTRIBUTION 2026-05-21: infrastructure-failure early-bail
            // fix. A session that completed with runtime_status='error' AND zero
            // messages AND zero traces is the signature of an agent loop that
            // crashed before producing any user-visible work (server restart,
            // LLM provider 5xx, network timeout before first token). Without
            // this hook annotateOne returned 0 and the session never surfaced
            // to the LLM annotator → no outcome row → never reached the curator.
            // Write one synthetic agent_error=true signal so STEP 2 of the
            // annotator pipeline can later mark these sessions as
            // outcome=infrastructure_failure (see SessionAnnotationConstants
            // OUTCOME_INFRASTRUCTURE_FAILURE).
            //
            // Guard ordering matters: messageCount==0 + runtimeStatus=='error'
            // are both required so we don't false-positive on sessions that
            // are mid-creation (no traces yet but messages exist) or that
            // ended idle / completed cleanly with zero traces (e.g. a session
            // that was created but never sent a chat message).
            if (session.getMessageCount() == 0 && "error".equals(session.getRuntimeStatus())) {
                Long insertedId = sessionAnnotationRepository.upsertSkipDuplicate(
                        sessionId,
                        "agent_error",
                        "true",
                        SessionAnnotationEntity.SOURCE_SIGNAL,
                        new BigDecimal("1.00"),
                        null);
                if (insertedId != null) {
                    log.info("[signal] sessionId={} 0-trace+0-msg+error → wrote synthetic agent_error signal", sessionId);
                    return 1;
                }
                log.debug("[signal] sessionId={} 0-trace+0-msg+error agent_error already annotated — skipping", sessionId);
                return 0;
            }
            // Otherwise: no traces just means the session has nothing to signal
            // yet (e.g. session completed but traces still ingesting; next
            // hourly run will pick it up via overlapping window).
            return 0;
        }

        // Pick the root trace = the one whose rootTraceId == its own traceId (or
        // fallback to the most recent if none self-identify; the SQL ORDER BY
        // already favors recent).
        LlmTraceEntity primary = traces.stream()
                .filter(t -> Objects.equals(t.getTraceId(), t.getRootTraceId()))
                .findFirst()
                .orElse(traces.get(0));

        List<String> traceIds = traces.stream().map(LlmTraceEntity::getTraceId).toList();
        List<LlmSpanEntity> spans = llmSpanRepository.findByTraceIdInOrderByStartedAtAsc(traceIds);

        int totalTokens = traces.stream()
                .mapToInt(t -> t.getTotalInputTokens() + t.getTotalOutputTokens())
                .sum();
        int totalToolCalls = traces.stream().mapToInt(LlmTraceEntity::getToolCallCount).sum();
        int totalLlmCalls = (int) spans.stream().filter(sp -> "llm".equals(sp.getKind())).count();

        List<String> reasons = TraceScenarioImportService.detectReasons(
                primary, spans, totalTokens, totalToolCalls, totalLlmCalls, DEFAULT_MIN_TOKENS);

        if (reasons.isEmpty()) {
            return 0;
        }

        int written = 0;
        BigDecimal fullConfidence = new BigDecimal("1.00");
        for (String reason : reasons) {
            // V1 W2 fix: use native ON CONFLICT DO NOTHING to keep the per-row
            // dedup signal without aborting the outer transaction on conflict.
            // null = already-existed (UNIQUE skip); non-null = newly inserted id.
            Long insertedId = sessionAnnotationRepository.upsertSkipDuplicate(
                    sessionId,
                    reason,
                    "true",
                    SessionAnnotationEntity.SOURCE_SIGNAL,
                    fullConfidence,
                    null);
            if (insertedId != null) {
                written++;
            } else {
                log.debug("[signal] sessionId={} reason={} already annotated — skipping",
                        sessionId, reason);
            }
        }
        return written;
    }

    /**
     * Tool-facing DTO for the LLM-annotation queue. Kept here (not in {@code dto/})
     * because it's an internal hand-off type between this service and
     * {@link com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool} —
     * not an external REST contract.
     */
    public record SessionNeedingLlmDto(String sessionId, String agentName, List<String> signalReasons) {}
}
