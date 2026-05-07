package com.skillforge.server.service.event;

/**
 * Published by {@code ChatService.runLoop} in its teardown block whenever an
 * agent session loop reaches a terminal state (completed / cancelled / error /
 * aborted_by_hook / waiting_user).
 *
 * <p>Generic session-finished signal, decoupled from the SubAgent registry's
 * own {@code onSessionLoopFinished} callback (the SubAgent path keeps using its
 * direct invocation for the result-pump fast path). New consumers — e.g. the
 * P12 {@code ScheduledTaskExecutor} — listen to this event to track completion
 * without needing a special hook in {@code ChatService}.
 *
 * <p>Carries the minimal terminal state needed by listeners:
 * <ul>
 *   <li>{@code sessionId} — which session finished</li>
 *   <li>{@code finalMessage} — final assistant message (nullable; e.g. cancelled paths)</li>
 *   <li>{@code finalStatus} — terminal status: {@code completed} / {@code cancelled} /
 *       {@code error} / {@code aborted_by_hook} / {@code waiting_user}</li>
 *   <li>{@code userId} — owning user id, for downstream owner-checks</li>
 * </ul>
 *
 * <p>Listeners must be {@code @Async} or fast: this event fires inside the
 * loop teardown finally block and slow listeners delay loop cleanup.
 */
public record SessionLoopFinishedEvent(
        String sessionId,
        String finalMessage,
        String finalStatus,
        Long userId
) {
}
