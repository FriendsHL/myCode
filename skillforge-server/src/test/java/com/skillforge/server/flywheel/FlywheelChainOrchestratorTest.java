package com.skillforge.server.flywheel;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-PER-AGENT-RUN-NOW — unit tests for the polling-based chain
 * orchestrator. We directly invoke {@link FlywheelChainOrchestrator#tick()}
 * (visible-for-test) so the test does not depend on Spring scheduler timing.
 *
 * <p>The fake {@link Clock} lets us walk the TTL boundary without sleeping.
 */
@DisplayName("FlywheelChainOrchestrator")
class FlywheelChainOrchestratorTest {

    private SessionService sessionService;
    private MutableClock clock;
    private FlywheelChainOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        clock = new MutableClock(Instant.parse("2026-05-21T10:00:00Z"));
        orchestrator = new FlywheelChainOrchestrator(sessionService, clock);
    }

    @Test
    @DisplayName("registerAnnotatorEndHook: rejects blank sessionId / null Runnable")
    void register_validatesInput() {
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook("", () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook(null, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook("sess-A", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tick: annotator session still 'running' → hook NOT fired, stays pending")
    void tick_sessionStillRunning_hookNotFired() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-running", callCount::incrementAndGet);
        assertThat(orchestrator.isPending("sess-running")).isTrue();

        when(sessionService.getSession(eq("sess-running")))
                .thenReturn(session("sess-running", "running"));

        orchestrator.tick();

        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-running")).isTrue();
    }

    @Test
    @DisplayName("tick: annotator session 'waiting_user' (non-terminal) → hook NOT fired, stays pending (r2 F4 java-reviewer W-5)")
    void tick_sessionWaitingUser_hookNotFired() {
        // session-annotator never asks the user — but if a future session-
        // annotator variant or test-injection landed it in 'waiting_user',
        // orchestrator must treat it as non-terminal (only 'idle' / 'error'
        // qualify). Closes the test gap for the 4th runtimeStatus value.
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-waiting", callCount::incrementAndGet);
        assertThat(orchestrator.isPending("sess-waiting")).isTrue();

        when(sessionService.getSession(eq("sess-waiting")))
                .thenReturn(session("sess-waiting", "waiting_user"));

        orchestrator.tick();

        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-waiting")).isTrue();
    }

    @Test
    @DisplayName("tick: annotator session 'idle' (terminal) → hook fires exactly once + map cleared")
    void tick_sessionIdle_hookFiresOnceAndRemoved() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-A", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-A"))).thenReturn(session("sess-A", "idle"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(orchestrator.isPending("sess-A")).isFalse();
        assertThat(orchestrator.pendingSize()).isEqualTo(0);

        // Second tick should NOT re-fire — hook is gone from the map.
        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tick: annotator session 'error' is terminal too → hook fires")
    void tick_sessionError_hookFires() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-err", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-err"))).thenReturn(session("sess-err", "error"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(orchestrator.isPending("sess-err")).isFalse();
    }

    @Test
    @DisplayName("tick: SessionService throws → hook dropped (don't infinite-retry on a dead session)")
    void tick_sessionLookupThrows_hookDroppedDontLeak() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-gone", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-gone")))
                .thenThrow(new RuntimeException("SessionNotFoundException stub"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-gone")).isFalse();
    }

    @Test
    @DisplayName("tick: hook older than TTL → expired without firing")
    void tick_ttlExpired_hookRemovedSilently() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-stuck", callCount::incrementAndGet);

        // Advance clock past TTL — no need for any sessionService stub because
        // the TTL check should sweep the hook before lookup.
        clock.advance(FlywheelChainOrchestrator.HOOK_TTL.plusSeconds(1));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-stuck")).isFalse();
    }

    @Test
    @DisplayName("registerAnnotatorEndHook: re-register same sessionId replaces prior hook")
    void register_reregisterReplaces() {
        AtomicInteger first = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-A", first::incrementAndGet);
        orchestrator.registerAnnotatorEndHook("sess-A", second::incrementAndGet);

        when(sessionService.getSession(eq("sess-A"))).thenReturn(session("sess-A", "idle"));

        orchestrator.tick();
        assertThat(first.get()).isEqualTo(0);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tick: onComplete Runnable throws → exception swallowed, map still cleared")
    void tick_onCompleteThrows_doesNotPropagate_andMapCleared() {
        orchestrator.registerAnnotatorEndHook("sess-bad", () -> {
            throw new RuntimeException("hook impl bug");
        });
        when(sessionService.getSession(eq("sess-bad"))).thenReturn(session("sess-bad", "idle"));

        // Should not throw — orchestrator's tick swallows RuntimeException
        // from onComplete so one bad hook doesn't poison subsequent ticks.
        orchestrator.tick();

        assertThat(orchestrator.isPending("sess-bad")).isFalse();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static SessionEntity session(String id, String runtimeStatus) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(0L);
        s.setAgentId(0L);
        s.setRuntimeStatus(runtimeStatus);
        return s;
    }

    /** Test-only mutable clock so we can step through TTL boundaries deterministically. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
