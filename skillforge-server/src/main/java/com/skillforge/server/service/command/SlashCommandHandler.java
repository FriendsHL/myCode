package com.skillforge.server.service.command;

import com.skillforge.server.dto.CommandResult;

/**
 * P10 §4: contract for a single slash command (one of 8 — see PRD).
 *
 * <p>Handlers are auto-discovered by Spring (each implementation is a {@code @Component})
 * and registered into {@link SlashCommandService} keyed by exact {@link #getName()}.
 *
 * <p>INV-15 (precise name match): {@code /model} and {@code /models} are TWO separate
 * handlers; the service dispatcher uses {@code getName().equals(token)} (no prefix
 * fuzzy match) so {@code /model gpt-4o} is never absorbed by {@code /models}.
 *
 * <p>INV-11 (registry-based help): {@link #getDescription()} and {@link #getUsage()}
 * are consumed by {@code HelpCommandHandler} to render the help body — never
 * hardcode a command list elsewhere.
 */
public interface SlashCommandHandler {

    /**
     * Exact lowercase command name without the leading slash. Must be globally
     * unique (e.g. {@code "new"}, {@code "model"}, {@code "models"}). Used for
     * exact-match dispatch in {@link SlashCommandService}.
     */
    String getName();

    /** One-line human description for {@code /help} listing. */
    String getDescription();

    /** Usage hint for {@code /help} — e.g. {@code "/model <modelId>"}. */
    String getUsage();

    /**
     * Execute the command for this turn.
     *
     * @param userId    current user id (already ownership-checked by controller / router)
     * @param sessionId current session id (the session in scope when the user typed the command)
     * @param args      everything after the command token (may be empty / null)
     * @param context   platform context (dashboard vs channel) — handlers SHOULD remain
     *                  platform-agnostic where possible; only {@code /new} cares
     */
    CommandResult execute(Long userId, String sessionId, String args, ExecutionContext context);
}
