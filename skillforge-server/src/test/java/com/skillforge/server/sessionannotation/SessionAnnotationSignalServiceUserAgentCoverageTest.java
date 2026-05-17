package com.skillforge.server.sessionannotation;

import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING F7 red test (Phase 1.0):
 * the session-annotator LLM queue must include user-agent sessions even when
 * system-agent sessions dominate the recent signal stream.
 *
 * <p><b>Why this test exists</b> — 2026-05-17 production SQL audit:
 * <ul>
 *   <li>268 production sessions, 117 outcome (source=llm) annotations</li>
 *   <li>All 117 outcome annotations are on system-agent sessions
 *       (attribution-curator 63 / metrics-collector 24 / session-annotator 29 /
 *        memory-curator 1)</li>
 *   <li>110 user-agent sessions (Main Assistant 58 / Design 23 / Research 15 /
 *       Code 14) have <b>zero</b> outcome annotations</li>
 * </ul>
 *
 * <p>Phase 1.0 reconnaissance traced the root cause to
 * {@link SessionAnnotationSignalService#findSessionsNeedingLlmAnnotation(int)}:
 * <ol>
 *   <li>{@code findRecentByLimit("signal", cap*3=30)} returns the 30 most-recent
 *       signal rows ordered {@code createdAt DESC}</li>
 *   <li>Because attribution-curator + session-annotator run hourly via cron and
 *       produce 5-10 signal rows per run, the most-recent 30 signals are
 *       <b>always</b> system-agent rows. The latest user-agent signal in prod
 *       is from 2026-05-15; latest system-agent signal is from today's hourly
 *       run</li>
 *   <li>After LinkedHashMap insertion-order grouping + take-first-10, user-agent
 *       sessions never reach the LLM annotation queue (classic starvation)</li>
 * </ol>
 *
 * <p><b>Test setup mirrors the starvation scenario</b>: 25 system-agent signal
 * rows (most recent) + 3 user-agent signal rows (older). Current code groups
 * insertion-order, takes first 10 → all system → zero user agents reach the
 * queue → this test <b>fails red</b>.
 *
 * <p>The Phase 1.2 fix (TBD: either repository-level join on {@code t_agent.agent_type}
 * or service-level priority sort using agent type info) must guarantee at least
 * one user-agent session reaches the LLM queue when both are available — this
 * test then passes green.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAnnotationSignalService — F7 user agent LLM queue coverage")
class SessionAnnotationSignalServiceUserAgentCoverageTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmTraceRepository llmTraceRepository;
    @Mock private LlmSpanRepository llmSpanRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private SessionAnnotationSignalService service;

    @BeforeEach
    void setUp() {
        service = new SessionAnnotationSignalService(
                sessionRepository, llmTraceRepository, llmSpanRepository, sessionAnnotationRepository);
    }

    /**
     * Setup mirrors the 2026-05-17 production starvation scenario:
     * <ul>
     *   <li>25 system-agent signal rows on 25 distinct {@code sys-sess-*} sessions
     *       (most recent — system cron flooding the global signal stream)</li>
     *   <li>3 user-agent signal rows on 3 distinct {@code user-sess-*} sessions
     *       (older — sparse user-agent traffic)</li>
     * </ul>
     *
     * <p>Repository mocks reflect the Phase 1.2 3-tier strategy: Tier 1 fetches
     * user-agent signals (returns the 3 user rows); Tier 2 fetches system-agent
     * signals (returns the 25 system rows); Tier 3 catch-all isn't strictly
     * required for this scenario but is stubbed leniently for robustness.
     *
     * <p>Pre-Phase-1.2 the test was red because the implementation only used the
     * uniform {@code findRecentByLimit} query and the 25 system rows displaced the
     * 3 user rows in the cap=10 take. Post-Phase-1.2 the test passes because
     * Tier 1 surfaces all 3 user-agent sessions before the system tier runs.
     */
    @Test
    @DisplayName("F7: user agent sessions reach LLM queue under system-agent recent-signal domination")
    void findSessionsNeedingLlmAnnotation_includesUserAgents_underSystemDomination() {
        Instant now = Instant.parse("2026-05-17T10:00:00Z");

        // Phase 1.2 Tier 1: user-agent signals — newest first
        List<SessionAnnotationEntity> userSignals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            userSignals.add(signalRow("user-sess-" + i, "tool_failure", now.minusSeconds((30 + i) * 60L)));
        }
        when(sessionAnnotationRepository.findRecentBySourceAndAgentType(eq("signal"), eq("user"), anyInt()))
                .thenReturn(userSignals);

        // Phase 1.2 Tier 2: system-agent signals — newest first
        List<SessionAnnotationEntity> systemSignals = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            systemSignals.add(signalRow("sys-sess-" + i, "agent_error", now.minusSeconds(i * 60L)));
        }
        // Tier 2 is only invoked when Tier 1 result count < cap=10; here Tier 1 returns 3
        // unique sessions, so Tier 2 must be queried to fill the cap.
        when(sessionAnnotationRepository.findRecentBySourceAndAgentType(eq("signal"), eq("system"), anyInt()))
                .thenReturn(systemSignals);

        // Phase 1.2 Tier 3: catch-all fallback — only invoked if Tiers 1+2 still under cap.
        // 3 + 25 = 28 distinct sessions already ≥ cap=10, so Tier 3 isn't called.
        // Mark lenient so Mockito doesn't complain about the unused stub.
        lenient().when(sessionAnnotationRepository.findRecentByLimit(eq("signal"), anyInt()))
                .thenReturn(List.of());

        // No LLM annotations yet — every session is a fresh candidate.
        when(sessionAnnotationRepository.findSessionIdsWithSource(anyCollection(), eq("llm")))
                .thenReturn(List.of());

        // Mock session lookups: system agents on agentIds 100..124, user agents on 1..3.
        Map<String, SessionEntity> sessionMap = new HashMap<>();
        for (int i = 0; i < 25; i++) {
            sessionMap.put("sys-sess-" + i, session("sys-sess-" + i, 100L + i));
        }
        sessionMap.put("user-sess-0", session("user-sess-0", 1L));
        sessionMap.put("user-sess-1", session("user-sess-1", 2L));
        sessionMap.put("user-sess-2", session("user-sess-2", 3L));
        lenient().when(sessionRepository.findAllById(anyIterable())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<SessionEntity> out = new ArrayList<>();
            for (String id : ids) {
                if (sessionMap.containsKey(id)) out.add(sessionMap.get(id));
            }
            return out;
        });

        List<SessionAnnotationSignalService.SessionNeedingLlmDto> queue =
                service.findSessionsNeedingLlmAnnotation(10);

        long userInQueue = queue.stream()
                .map(SessionAnnotationSignalService.SessionNeedingLlmDto::sessionId)
                .filter(id -> id.startsWith("user-sess-"))
                .count();

        assertThat(userInQueue)
                .as("F7 (SYSTEM-AGENT-TYPING): user-agent sessions must reach the LLM " +
                        "annotation queue even when system-agent signals dominate the recent " +
                        "window. Pre-Phase-1.2 production state (2026-05-17 SQL audit): " +
                        "0 user-agent outcome annotations vs 117 system-agent outcome annotations. " +
                        "Phase 1.2 fix uses tiered queries (user first, system backfill) so " +
                        "all 3 user-agent sessions reach the queue.")
                .isGreaterThanOrEqualTo(1L);

        // Stronger assertion: with 3 user + 25 system available and cap=10, all 3 user
        // must be in the queue ahead of any system agent.
        List<String> queueIds = queue.stream()
                .map(SessionAnnotationSignalService.SessionNeedingLlmDto::sessionId)
                .toList();
        assertThat(queueIds.subList(0, 3))
                .as("Phase 1.2 priority order: all 3 user-agent sessions come before system agents")
                .containsExactlyInAnyOrder("user-sess-0", "user-sess-1", "user-sess-2");
    }

    /**
     * Phase 2 W1 regression test (java-reviewer r1 mandatory follow-up).
     *
     * <p><b>Pre-W1-fix bug</b>: the per-tier guard {@code reasonsBySession.size() < capped}
     * read a pre-dedup count. When Tier 1 returned ≥ {@code capped} user-agent
     * sessions <i>all of which</i> already had {@code source='llm'} annotations, the
     * guard saw {@code size >= capped}, skipped Tier 2/3, then the trailing dedup
     * stripped every entry → empty queue. In production this becomes the steady
     * state once user-agent annotation coverage builds up (the Phase 1.2 fix
     * actively drives toward that state).
     *
     * <p><b>Fix</b>: dedup LLM-annotated sessions after each tier (not just at the
     * end), so the next tier's guard measures the post-dedup count and backfills
     * when needed.
     *
     * <p>Test setup mirrors the production steady-state: 12 user-agent signal rows
     * on 12 distinct sessions (all 12 already LLM-annotated — exceeds cap=10);
     * 5 system-agent signal rows on fresh sessions (none LLM-annotated). Pre-fix
     * → guard sees Tier 1 size=12 ≥ cap=10, skips Tier 2/3, final dedup wipes all
     * 12 → empty queue. Post-fix → per-tier dedup after Tier 1 leaves size=0,
     * triggers Tier 2 → 5 system sessions → returned.
     */
    @Test
    @DisplayName("W1: Tier 2 backfills when Tier 1 user sessions are all already LLM-annotated")
    void findSessionsNeedingLlmAnnotation_tier1AllLlmAnnotated_tier2Backfills() {
        Instant now = Instant.parse("2026-05-17T10:00:00Z");

        // Tier 1: 12 user-agent signal rows (>= cap=10) on distinct sessions that ALL
        // already have llm annotation. This is the post-Phase-1.2 steady state once
        // user-agent annotation coverage builds up.
        List<SessionAnnotationEntity> userSignals = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            userSignals.add(signalRow("user-sess-" + i, "tool_failure", now.minusSeconds(i * 30L)));
        }
        when(sessionAnnotationRepository.findRecentBySourceAndAgentType(eq("signal"), eq("user"), anyInt()))
                .thenReturn(userSignals);

        // Tier 2: 5 system-agent signals on fresh sessions (no llm annotation).
        List<SessionAnnotationEntity> systemSignals = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            systemSignals.add(signalRow("sys-" + i, "agent_error", now.minusSeconds(60L * (i + 20))));
        }
        when(sessionAnnotationRepository.findRecentBySourceAndAgentType(eq("signal"), eq("system"), anyInt()))
                .thenReturn(systemSignals);

        // Tier 3 mocked empty for focus (would fire post-fix because Tier 1+2 dedup'd
        // size = 5 < cap = 10).
        lenient().when(sessionAnnotationRepository.findRecentByLimit(eq("signal"), anyInt()))
                .thenReturn(List.of());

        // W1 fix invariant: findSessionIdsWithSource now called once per tier.
        // Stub it as a function of input — user sessions are all already llm-annotated;
        // system sessions are fresh.
        when(sessionAnnotationRepository.findSessionIdsWithSource(anyCollection(), eq("llm")))
                .thenAnswer(inv -> {
                    java.util.Collection<String> ids = inv.getArgument(0);
                    java.util.List<String> result = new ArrayList<>();
                    for (String id : ids) {
                        if (id.startsWith("user-sess-")) result.add(id);
                    }
                    return result;
                });

        // Session lookups for DTO build.
        Map<String, SessionEntity> sessionMap = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            sessionMap.put("sys-" + i, session("sys-" + i, 100L + i));
        }
        lenient().when(sessionRepository.findAllById(anyIterable())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<SessionEntity> out = new ArrayList<>();
            for (String id : ids) {
                if (sessionMap.containsKey(id)) out.add(sessionMap.get(id));
            }
            return out;
        });

        List<SessionAnnotationSignalService.SessionNeedingLlmDto> queue =
                service.findSessionsNeedingLlmAnnotation(10);

        // Pre-W1-fix: queue empty (Tier 2 skipped by pre-dedup guard since 12 >= 10;
        // then trailing dedup wipes all 12 Tier 1 entries).
        // Post-W1-fix: queue has all 5 system sessions because per-tier dedup leaves
        // Tier 1 at size=0, triggering Tier 2 backfill.
        assertThat(queue)
                .as("W1: Tier 2 must backfill when Tier 1 fully dedup'd to empty — " +
                        "pre-fix would return [], post-fix returns the 5 fresh system sessions")
                .hasSize(5);
        assertThat(queue.stream()
                .map(SessionAnnotationSignalService.SessionNeedingLlmDto::sessionId)
                .toList())
                .containsExactlyInAnyOrder("sys-1", "sys-2", "sys-3", "sys-4", "sys-5");
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private static SessionEntity session(String id, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setOrigin("production");
        s.setCompletedAt(Instant.parse("2026-05-17T09:00:00Z"));
        return s;
    }

    private static SessionAnnotationEntity signalRow(String sessionId, String reason, Instant createdAt) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType(reason);
        a.setAnnotationValue("true");
        a.setSource("signal");
        a.setCreatedAt(createdAt);
        return a;
    }
}
