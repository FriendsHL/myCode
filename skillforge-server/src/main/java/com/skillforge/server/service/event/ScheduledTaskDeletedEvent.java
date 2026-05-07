package com.skillforge.server.service.event;

/**
 * Published by {@code ScheduledTaskService} after a task row is deleted.
 *
 * <p>BE-2's {@code UserTaskScheduler} listens and calls {@code unschedule(taskId)}.
 * Includes {@code creatorUserId} purely for log / audit context — the listener does
 * not need it for the unschedule call itself.
 */
public record ScheduledTaskDeletedEvent(Long taskId, Long creatorUserId) {
}
