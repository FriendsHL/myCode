package com.skillforge.core.reminder;

/**
 * REMINDER-MVP: pluggable producer of one {@code <system-reminder>} entry per turn.
 *
 * <p>Each source owns a single concern (memory age / context usage / file activity / …) and
 * decides per-turn whether to emit (debounce + threshold) and what to render. Sources are
 * registered in {@link ReminderBuilder} in the order defined by PRD D7 — {@code ContextUsage}
 * → {@code MemoryAge} → {@code FileActivity} so the most-actionable signal lands first under
 * the shared 5K token budget.
 *
 * <p>Implementations must be thread-safe: the same instance is reused across many sessions
 * concurrently. Q2 (cache-friendly migration): per-source debounce state lives on
 * {@link ReminderBuilder} keyed by {@code (sessionId, sourceName)} via
 * {@link ReminderBuilder#getLastEmitted(String, String)} /
 * {@link ReminderBuilder#setLastEmitted(String, String, int)}. Sources reach the builder via
 * {@link ReminderContext#getReminderBuilder()}.
 *
 * <p>Failure handling: every source MUST swallow its own internal exceptions and degrade to
 * "don't emit" (return false / null). The orchestrator ({@link ReminderBuilder}) wraps each
 * call in try/catch as a defense-in-depth net but sources are expected to be best-effort.
 */
public interface ReminderSource {

    /**
     * @return stable name (PascalCase or kebab-case) used as the per-session debounce key
     *         on {@link ReminderBuilder} and as the yaml configuration prefix. Must NEVER
     *         change once a source ships.
     */
    String getName();

    /**
     * Cheap "should I render now?" check — runs every turn before {@link #emit}.
     *
     * <p>Typical implementation: {@code enabled && (now - lastEmitted >= interval)
     * && thresholdSatisfied}.
     *
     * @param ctx per-iteration context, never {@code null}
     * @return {@code true} → call {@link #emit}; {@code false} → skip this source for this turn
     */
    boolean shouldEmit(ReminderContext ctx);

    /**
     * Render the reminder entry.  Must update the source's own debounce state via
     * {@link ReminderBuilder#setLastEmitted(String, String, int)} (read from
     * {@link ReminderContext#getReminderBuilder()}) before returning.
     *
     * <p>May return {@code null} if a late filter (e.g. all candidates are too young to mention)
     * decides nothing should be emitted after all — caller treats {@code null} the same as
     * {@code shouldEmit=false} (no debounce update side-effect required).
     *
     * @param ctx per-iteration context, never {@code null}
     * @return the rendered {@link ReminderEntry}, or {@code null} to skip
     */
    ReminderEntry emit(ReminderContext ctx);
}
