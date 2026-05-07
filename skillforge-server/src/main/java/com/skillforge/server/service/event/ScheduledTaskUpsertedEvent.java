package com.skillforge.server.service.event;

/**
 * Published by {@code ScheduledTaskService} after a task is created or updated.
 *
 * <p>BE-2's {@code UserTaskScheduler} listens and calls {@code reschedule(taskId)} —
 * this keeps BE-1 free of any scheduler dependency, satisfying the
 * "BE-1 doesn't import BE-2" boundary in the implementation brief §9.
 *
 * <p>Carries only {@code taskId}; the listener loads the latest entity itself so it
 * always picks up the post-commit state (avoid stale data races if the event fires
 * before the transaction commits — Spring's default event publishing is synchronous,
 * but the listener should still re-read).
 */
public record ScheduledTaskUpsertedEvent(Long taskId) {
}
