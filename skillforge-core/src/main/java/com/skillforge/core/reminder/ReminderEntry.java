package com.skillforge.core.reminder;

/**
 * REMINDER-MVP: one piece of reminder text emitted by a single {@link ReminderSource}.
 *
 * <p>{@link ReminderBuilder} concatenates emitted entries into the final
 * {@code <system-reminder>} block and budgets total tokens (PRD D7) using
 * {@link #estimatedTokens()}.
 *
 * <p>Immutable record — sources MUST return a fresh instance per emit so the builder
 * can safely accumulate.
 */
public record ReminderEntry(String text, int estimatedTokens) {
    public ReminderEntry {
        if (text == null) text = "";
        if (estimatedTokens < 0) estimatedTokens = 0;
    }
}
