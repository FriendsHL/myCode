package com.skillforge.core.reminder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryAgeSourceTest {

    private ReminderBuilder builder;
    private FakeStats stats;

    /**
     * Counts call-site hits so tests can prove W2 — exactly one DB roundtrip per emit instead
     * of three.  shouldEmit is now O(1) (no DB calls), and emit calls getStats once.
     */
    private static class FakeStats implements MemoryAgeStatsProvider {
        long active = 10;
        long stale = 0;
        Optional<Instant> last = Optional.empty();
        boolean throwOnGet = false;
        final AtomicInteger getStatsCalls = new AtomicInteger();

        @Override public Stats getStats(Long userId, int daysThreshold) {
            getStatsCalls.incrementAndGet();
            if (throwOnGet) throw new RuntimeException("db down");
            return new Stats(active, stale, last);
        }
    }

    @BeforeEach
    void setUp() {
        builder = new ReminderBuilder(List.of(), 5_000, true);
        stats = new FakeStats();
    }

    private ReminderContext ctxAtTurn(int turn) {
        return new ReminderContext("s1", 7L, turn,
                Collections.emptyList(), 200_000,
                "", List.of(), 0, null, null, builder);
    }

    @Test
    @DisplayName("disabled source never emits")
    void disabled_neverEmits() {
        stats.stale = 5;
        MemoryAgeSource src = new MemoryAgeSource(stats, false, 5, 7);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isFalse();
    }

    @Test
    @DisplayName("shouldEmit makes ZERO DB calls (W2: stale check moved to emit)")
    void shouldEmit_noDbCalls() {
        stats.stale = 5;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        for (int t = 0; t < 10; t++) {
            src.shouldEmit(ctxAtTurn(t));
        }
        assertThat(stats.getStatsCalls.get())
                .as("shouldEmit must not call getStats — that's emit's job")
                .isZero();
    }

    @Test
    @DisplayName("emit calls getStats exactly once per call (W2: was 3 round-trips before)")
    void emit_singleDbCall() {
        stats.stale = 5;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNotNull();
        assertThat(stats.getStatsCalls.get()).isOne();
    }

    @Test
    @DisplayName("emit returns null when staleCount=0 — no debounce state written")
    void emit_zeroStale_returnsNullNoDebounceUpdate() {
        stats.stale = 0;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNull();
        assertThat(builder.getLastEmitted("s1", "memory-age")).isNull();
    }

    @Test
    @DisplayName("debounce: after emit at turn 0, next 4 turns suppressed; turn 5 allowed again")
    void debounce_5turns() {
        stats.stale = 1;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isTrue();
        ReminderEntry e0 = src.emit(ctxAtTurn(0));
        assertThat(e0).isNotNull();
        for (int t = 1; t < 5; t++) {
            assertThat(src.shouldEmit(ctxAtTurn(t)))
                    .as("turn %d should be debounced", t).isFalse();
        }
        assertThat(src.shouldEmit(ctxAtTurn(5))).isTrue();
    }

    @Test
    @DisplayName("emit text contains active / stale / threshold and lastRecalled when present")
    void emit_format() {
        stats.active = 12;
        stats.stale = 4;
        stats.last = Optional.of(Instant.now().minusSeconds(120));
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNotNull();
        assertThat(e.text()).contains("12 active entries");
        assertThat(e.text()).contains("4 stale (>7 days)");
        assertThat(e.text()).contains("last recalled");
        assertThat(e.estimatedTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("emit without lastRecalled omits the trailing clause")
    void emit_noLastRecalled() {
        stats.active = 3;
        stats.stale = 1;
        stats.last = Optional.empty();
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e.text()).doesNotContain("last recalled");
    }

    @Test
    @DisplayName("emit updates per-session lastEmitted state when staleCount > 0")
    void emit_updatesBuilderState() {
        stats.stale = 1;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        assertThat(builder.getLastEmitted("s1", "memory-age")).isNull();
        src.emit(ctxAtTurn(7));
        assertThat(builder.getLastEmitted("s1", "memory-age")).isEqualTo(7);
    }

    @Test
    @DisplayName("stats provider exception → emit degrades to null (no propagate)")
    void statsThrows_degrades() {
        stats.throwOnGet = true;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isTrue();
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNull();
    }

    @Test
    @DisplayName("null userId → shouldEmit=false")
    void nullUserId_skip() {
        stats.stale = 1;
        MemoryAgeSource src = new MemoryAgeSource(stats, true, 5, 7);
        ReminderContext ctx = new ReminderContext("s1", null, 0,
                Collections.emptyList(), 200_000,
                "", List.of(), 0, null, null, builder);
        assertThat(src.shouldEmit(ctx)).isFalse();
    }

    @Test
    @DisplayName("formatAge: < 60s, < 60min, < 24h, days")
    void formatAge_buckets() {
        Instant now = Instant.now();
        assertThat(MemoryAgeSource.formatAge(now.minusSeconds(45))).contains("s ago");
        assertThat(MemoryAgeSource.formatAge(now.minusSeconds(90))).contains("min ago");
        assertThat(MemoryAgeSource.formatAge(now.minusSeconds(3600 * 3))).contains("h ago");
        assertThat(MemoryAgeSource.formatAge(now.minusSeconds(86400L * 5))).contains("d ago");
    }
}
