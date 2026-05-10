package com.skillforge.core.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REMINDER-MVP: orchestrator that runs every {@link ReminderSource} per turn, accumulates the
 * resulting {@link ReminderEntry}s under a shared token budget (PRD D7), and wraps the final
 * text in a {@code <system-reminder>...</system-reminder>} block.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Global feature flag {@code globalEnabled=false} → {@link #build} returns {@code ""}
 *       (caller skips append).</li>
 *   <li>Iterates {@code sources} in their constructor-supplied order (D7:
 *       ContextUsage → MemoryAge → FileActivity).</li>
 *   <li>For each source, calls {@code shouldEmit} → {@code emit}; {@code null} entry skipped.</li>
 *   <li>Token budget cumulative: once {@code totalTokens + entry.estimatedTokens >
 *       totalBudgetTokens} the loop breaks (later sources skipped). The first entry is always
 *       admitted even if oversized — otherwise an oversized critical reminder (Context warning)
 *       would silently disappear.</li>
 *   <li>Empty result → return {@code ""} (no wrapper emitted).</li>
 *   <li>Source exceptions are caught and logged at WARN; the source is skipped, the build
 *       continues. Reminders must NEVER block the LLM call.</li>
 * </ul>
 *
 * <p>Q2 cache-friendly migration (2026-05-10): per-source debounce state lives here, keyed by
 * {@code sessionId → sourceName → turnIndex}. Previously kept on {@link
 * com.skillforge.core.engine.LoopContext} (per-loop). Builder lifetime is process-scope (Spring
 * singleton); restart clears state, matching the prior per-loop reset semantics for any agent
 * that wasn't actively in a long-running loop. Different sessions never share state. Single
 * session is serialised by ChatService user-msg path (DB lock), so plain {@link ConcurrentHashMap}
 * is sufficient — concurrent writes only happen across sessions.
 *
 * <p>Framework-free POJO (same model as P9-5 RecoveryPayloadBuilder): no Spring annotations.
 * Server module wires sources, budget and global flag via constructor in {@code SkillForgeConfig}.
 */
public class ReminderBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReminderBuilder.class);

    public static final int DEFAULT_TOTAL_BUDGET_TOKENS = 5_000;

    private final List<ReminderSource> sources;
    private final int totalBudgetTokens;
    private final boolean globalEnabled;

    /**
     * Q2: per-session per-source debounce state.
     * {@code sessionId → (sourceName → lastEmittedTurnIndex)}.
     * Restart wipes all state (acceptable — prior per-loop state had identical semantics for
     * any session whose loop was not currently in flight).
     */
    private final Map<String, Map<String, Integer>> debounceBySession = new ConcurrentHashMap<>();

    /**
     * @param sources           ordered source list (D7); {@code null} → empty list
     * @param totalBudgetTokens shared budget in tokens (≤ 0 falls back to {@link #DEFAULT_TOTAL_BUDGET_TOKENS})
     * @param globalEnabled     master switch; false → {@link #build} short-circuits to ""
     */
    public ReminderBuilder(List<ReminderSource> sources, int totalBudgetTokens, boolean globalEnabled) {
        this.sources = sources != null ? List.copyOf(sources) : Collections.emptyList();
        this.totalBudgetTokens = totalBudgetTokens > 0 ? totalBudgetTokens : DEFAULT_TOTAL_BUDGET_TOKENS;
        this.globalEnabled = globalEnabled;
    }

    public boolean isGlobalEnabled() { return globalEnabled; }
    public int getTotalBudgetTokens() { return totalBudgetTokens; }

    /**
     * Q2: read the last-emitted turn index for {@code sourceName} on {@code sessionId}, or
     * {@code null} if the source has never emitted in this session. Sources call this from
     * {@code shouldEmit} to enforce Turn Count Debounce (PRD D3).
     *
     * <p>Defensive null sessionId / sourceName → returns {@code null} (always allow).
     */
    public Integer getLastEmitted(String sessionId, String sourceName) {
        if (sessionId == null || sourceName == null) return null;
        Map<String, Integer> bySource = debounceBySession.get(sessionId);
        if (bySource == null) return null;
        return bySource.get(sourceName);
    }

    /**
     * Q2: record that {@code sourceName} just emitted at {@code turnIndex} on {@code sessionId}
     * (typically {@code messages.size()} at emit time). Subsequent {@code shouldEmit} checks
     * for the same source on the same session compare against this value to enforce per-source
     * debounce intervals.
     *
     * <p>Defensive null sessionId / sourceName → no-op.
     */
    public void setLastEmitted(String sessionId, String sourceName, int turnIndex) {
        if (sessionId == null || sourceName == null) return;
        debounceBySession
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(sourceName, turnIndex);
    }

    /**
     * BE-W1 fix: drop all per-source debounce state for a session. Called on session deletion
     * by SessionService so the singleton's {@code debounceBySession} doesn't accumulate dead
     * entries (without this, deleted sessions remain in memory until process restart — ~20MB
     * for 100K deletions).
     *
     * <p>Defensive null sessionId → no-op. Idempotent.
     */
    public void clearSession(String sessionId) {
        if (sessionId == null) return;
        debounceBySession.remove(sessionId);
    }

    /**
     * Build the reminder text for one turn.
     *
     * @param ctx per-iteration context; {@code null} returns {@code ""}
     * @return either {@code "<system-reminder>\n…\n</system-reminder>\n"} or {@code ""}
     *         when nothing was emitted / globally disabled.
     *         Q2 (cache-friendly): no leading {@code "\n"} — caller (ChatService ContentBlock)
     *         doesn't need the legacy promptSuffix-join newline. Recovery payloads handle their
     *         own framing.
     */
    public String build(ReminderContext ctx) {
        if (!globalEnabled) return "";
        if (ctx == null) return "";

        List<ReminderEntry> entries = new ArrayList<>();
        int totalTokens = 0;

        for (ReminderSource source : sources) {
            ReminderEntry entry = invokeSafely(source, ctx);
            if (entry == null) continue;
            // Budget gate: always admit the first entry so an oversized critical signal still
            // lands. Subsequent entries are gated.
            if (!entries.isEmpty() && totalTokens + entry.estimatedTokens() > totalBudgetTokens) {
                break;
            }
            entries.add(entry);
            totalTokens += entry.estimatedTokens();
        }

        if (entries.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(256);
        sb.append("<system-reminder>\n");
        for (ReminderEntry e : entries) {
            sb.append(e.text());
            if (!e.text().endsWith("\n")) sb.append('\n');
        }
        sb.append("</system-reminder>\n");
        return sb.toString();
    }

    /** Run source.shouldEmit + emit, wrapping any throw in WARN log + null return. */
    private ReminderEntry invokeSafely(ReminderSource source, ReminderContext ctx) {
        if (source == null) return null;
        try {
            if (!source.shouldEmit(ctx)) return null;
            return source.emit(ctx);
        } catch (Exception e) {
            log.warn("ReminderSource '{}' failed (skipped): {}",
                    safeName(source), e.toString());
            return null;
        }
    }

    private static String safeName(ReminderSource source) {
        try { return source.getName(); } catch (Exception ignored) { return "?"; }
    }
}
