package com.skillforge.core.reminder;

import java.time.Instant;
import java.util.Optional;

/**
 * REMINDER-MVP: per-user stats source for {@link MemoryAgeSource}.
 *
 * <p>Lives in {@code skillforge-core} so the source has no compile-time link to
 * {@code skillforge-server.MemoryService}. Server-side wiring provides a single-method
 * implementation that runs all 3 stats in one transaction (W2 fix: was 3 round-trips +
 * a TOCTOU race window between shouldEmit / emit).
 *
 * <p>Implementations MUST be best-effort: any internal exception should bubble up the
 * normal Java way, but {@link MemoryAgeSource} wraps calls in try/catch to ensure the
 * reminder pipeline never blocks the LLM call.
 */
public interface MemoryAgeStatsProvider {

    /**
     * Aggregate snapshot of a user's ACTIVE memory state. Returned in one transactional
     * call so {@link Stats#staleCount} and {@link Stats#lastRecalledAt} are consistent
     * (no race window between separate queries).
     */
    record Stats(long activeCount, long staleCount, Optional<Instant> lastRecalledAt) {
        public static final Stats EMPTY =
                new Stats(0L, 0L, Optional.empty());
    }

    /**
     * @param userId            target user; null returns {@link Stats#EMPTY}
     * @param staleDaysThreshold age cutoff for "stale" classification; non-positive returns 0 stale
     * @return aggregate snapshot; never null. Implementations MAY throw on DB failure —
     *         {@link MemoryAgeSource} wraps the call defensively.
     */
    Stats getStats(Long userId, int staleDaysThreshold);
}
