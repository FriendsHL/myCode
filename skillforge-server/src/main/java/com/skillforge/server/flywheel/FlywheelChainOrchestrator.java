package com.skillforge.server.flywheel;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21) — on-demand per-agent loop trigger
 * step-chaining helper.
 *
 * <p>The controller endpoint
 * {@code POST /api/flywheel/agents/{agentId}/run-loop} fires the
 * {@code session-annotator} agent first; the {@code attribution-dispatcher}
 * must wait until the annotator's chat-loop reaches a terminal state
 * ({@code idle} / {@code error}) before it runs, otherwise the dispatcher
 * scans stale {@code t_session_pattern} rows (Q1 of the brief).
 *
 * <p>Design choice (Q2 of the brief, b option — polling fallback over
 * Spring-event-listener wiring): {@link Scheduled} polls a
 * {@code ConcurrentHashMap<sessionId, PendingHook>} every
 * {@value #POLL_INTERVAL_MS} ms. When the registered annotator session's
 * {@link SessionEntity#getRuntimeStatus()} is terminal the hook is removed +
 * its {@link Runnable} runs. Reasons polling beats the alternatives:
 * <ul>
 *   <li><b>No new event on ChatService</b> — the brief flags ChatService as a
 *       Iron-Law-protected core file (touching it risks the 4-byte
 *       persistence-shape invariant on the Message JSON path); a polling
 *       helper lives entirely inside this new component;</li>
 *   <li><b>Bounded fan-out</b> — the polling map is keyed by sessionId so even
 *       under rapid clicks (operator hammering the button) we never spawn
 *       runaway scheduler work; the map size = number of concurrent in-flight
 *       per-agent triggers, naturally bounded by operator UI cadence;</li>
 *   <li><b>Crash-safe</b> — orphan hooks expire after {@link #HOOK_TTL} so a
 *       server restart between annotator-fire and dispatcher-fire doesn't
 *       leak the hook map forever. The dispatcher then never fires for that
 *       click, but the user can re-click; the annotator session itself is
 *       persisted normally.</li>
 * </ul>
 *
 * <p>Concurrency:
 * <ul>
 *   <li>{@link #pending} is a {@link ConcurrentHashMap} — registrations from
 *       the controller thread vs reads from the scheduler thread are safe;</li>
 *   <li>each tick uses {@link Map#computeIfPresent} (atomic check-and-remove)
 *       to ensure a hook fires <b>exactly once</b> even if two scheduler ticks
 *       race against a slow {@code Runnable};</li>
 *   <li>the hook's {@code Runnable} is invoked on the scheduler thread —
 *       chatAsync is itself non-blocking (it just enqueues to
 *       {@code chatLoopExecutor}), so the next tick's latency is not affected.</li>
 * </ul>
 */
@Component
public class FlywheelChainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlywheelChainOrchestrator.class);

    /**
     * Polling cadence in milliseconds. Chosen as 2s — annotator chat-loops in
     * production take 30s–2min, so a 2s tick gives ~1s expected dispatcher
     * delay after annotator completion (acceptable for on-demand operator UX)
     * without burning CPU on idle ticks. Lower values would not help (annotator
     * runtime is the bottleneck); higher values would noticeably delay the
     * dispatcher fire from the operator's perspective.
     */
    static final long POLL_INTERVAL_MS = 2_000L;

    /**
     * Hook TTL — drop any registration that has been waiting longer than this
     * for its annotator session to reach a terminal state. Picked to comfortably
     * exceed the worst-case annotator run (~2 min observed in dogfood +
     * generous headroom for LLM provider 5xx retries / network blips). Once
     * exceeded the hook is removed without firing — the operator can re-click
     * "Run loop now" if they still want a dispatcher fan-out.
     */
    static final Duration HOOK_TTL = Duration.ofMinutes(10);

    /**
     * Set of session terminal-state runtimeStatus values. Stays in sync with
     * {@code SessionEntity.runtimeStatus} = {@code idle} / {@code running} /
     * {@code waiting_user} / {@code error}. Terminal = {@code idle} (success
     * exit) or {@code error} (engine threw / LLM provider failure). {@code
     * waiting_user} is NOT terminal — the annotator never asks the user, so
     * if it ends up there something is wrong, but we don't fire the dispatcher
     * either (treat as still-running until either the operator intervenes or
     * the TTL sweeps the hook). Hard-coded constants vs an enum to mirror
     * {@code ChatService}'s string-based state machine.
     */
    private static final String STATUS_IDLE = "idle";
    private static final String STATUS_ERROR = "error";

    private final SessionService sessionService;
    private final Clock clock;

    /**
     * Pending per-annotator-session hooks. Keyed by sessionId so re-registering
     * for the same session is a no-op (operator double-click safety; the
     * controller already creates a fresh session per click, but a defensive
     * upsert costs nothing).
     */
    private final Map<String, PendingHook> pending = new ConcurrentHashMap<>();

    public FlywheelChainOrchestrator(SessionService sessionService, Clock clock) {
        this.sessionService = sessionService;
        this.clock = clock;
    }

    /**
     * Pending hook record. {@code registeredAt} drives the TTL sweep;
     * {@code onComplete} is the {@link Runnable} the scheduler fires once the
     * annotator session reaches a terminal {@code runtimeStatus}.
     */
    record PendingHook(String annotatorSessionId, Instant registeredAt, Runnable onComplete) {}

    /**
     * Register a one-shot {@link Runnable} to fire after the
     * {@code annotatorSessionId} session reaches a terminal
     * {@code runtimeStatus}. Non-blocking: returns immediately; the polling
     * scheduler will detect completion and run the hook. Re-registering the
     * same {@code annotatorSessionId} silently replaces the prior hook
     * (operator double-click safety).
     *
     * <p>Visible-for-test: the unit test directly invokes {@link #tick()} so
     * polling latency does not interfere with assertions.
     */
    public void registerAnnotatorEndHook(String annotatorSessionId, Runnable onComplete) {
        if (annotatorSessionId == null || annotatorSessionId.isBlank()) {
            throw new IllegalArgumentException("annotatorSessionId must be non-blank");
        }
        if (onComplete == null) {
            throw new IllegalArgumentException("onComplete Runnable must be non-null");
        }
        PendingHook hook = new PendingHook(annotatorSessionId, clock.instant(), onComplete);
        PendingHook prior = pending.put(annotatorSessionId, hook);
        if (prior != null) {
            log.info("[FlywheelChain] replaced existing hook for annotatorSessionId={}", annotatorSessionId);
        } else {
            log.info("[FlywheelChain] registered hook for annotatorSessionId={} (pendingSize={})",
                    annotatorSessionId, pending.size());
        }
    }

    /**
     * Scheduler tick: walk {@link #pending} once, fire hooks whose annotator
     * session has reached terminal {@code runtimeStatus}, drop expired hooks.
     * Public visibility for unit tests.
     *
     * <p>Per-hook isolation: each hook's lookup / run is wrapped in try/catch
     * so a single bad session (deleted / DB hiccup) does not poison the rest
     * of the tick — same per-iteration narrow-catch shape as
     * {@code AttributionDispatcherService.dispatchPendingPatterns}.
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void tick() {
        if (pending.isEmpty()) return;
        Instant now = clock.instant();
        Instant ttlCutoff = now.minus(HOOK_TTL);

        // Iterate via Iterator so we can compose ConcurrentHashMap-safe atomic
        // remove (via computeIfPresent inside the body) with TTL expiry.
        Iterator<Map.Entry<String, PendingHook>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingHook> entry = it.next();
            String sessionId = entry.getKey();
            PendingHook hook = entry.getValue();

            // TTL sweep: drop without firing.
            if (hook.registeredAt().isBefore(ttlCutoff)) {
                pending.remove(sessionId, hook);
                log.warn("[FlywheelChain] hook for annotatorSessionId={} expired after TTL={}; "
                        + "annotator never reached terminal runtimeStatus", sessionId, HOOK_TTL);
                continue;
            }

            try {
                SessionEntity session = sessionService.getSession(sessionId);
                String runtimeStatus = session.getRuntimeStatus();
                if (STATUS_IDLE.equals(runtimeStatus) || STATUS_ERROR.equals(runtimeStatus)) {
                    // Atomic compare-and-remove: if the hook reference is still
                    // the one we matched, swap it out + fire. If another thread
                    // (e.g. a re-registration) replaced it, leave the new hook
                    // alone — next tick will handle it.
                    PendingHook[] firedRef = new PendingHook[1];
                    pending.computeIfPresent(sessionId, (k, current) -> {
                        if (current == hook) {
                            firedRef[0] = current;
                            return null;  // remove
                        }
                        return current;
                    });
                    if (firedRef[0] != null) {
                        log.info("[FlywheelChain] annotator terminal (status={}); firing hook for sessionId={}",
                                runtimeStatus, sessionId);
                        try {
                            firedRef[0].onComplete().run();
                        } catch (RuntimeException e) {
                            log.error("[FlywheelChain] onComplete hook threw for annotatorSessionId={}: {}",
                                    sessionId, e.getMessage(), e);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // SessionNotFoundException / DataAccessException — drop the
                // hook because we can't ever fire it. Same narrow-catch shape
                // as AttributionDispatcherService.dispatchPendingPatterns
                // (per-pattern try/catch lesson: one bad row must not poison
                // the rest of the tick).
                log.warn("[FlywheelChain] dropping hook for annotatorSessionId={} (lookup failed): {}",
                        sessionId, e.getMessage());
                pending.remove(sessionId, hook);
            }
        }
    }

    /**
     * Visible-for-test: snapshot the current pending map size. Public so unit
     * tests can assert "hook was registered" and "hook was fired+removed"
     * without poking the internal field reflectively.
     */
    public int pendingSize() {
        return pending.size();
    }

    /**
     * Visible-for-test: a sessionId is currently registered. Used by tests
     * to assert pre/post-tick state transitions.
     */
    public boolean isPending(String annotatorSessionId) {
        return pending.containsKey(annotatorSessionId);
    }

    /**
     * Visible-for-test only: snapshot of the entries via the supplied
     * {@link Function}; not exposed in production paths. Kept off public API
     * because the {@link PendingHook} record is package-private.
     */
    Map<String, PendingHook> snapshotForTest() {
        return new HashMap<>(pending);
    }
}
