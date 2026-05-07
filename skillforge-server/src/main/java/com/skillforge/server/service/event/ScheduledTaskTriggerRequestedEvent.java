package com.skillforge.server.service.event;

/**
 * Published by {@code ScheduledTaskService} when a manual trigger is requested
 * via {@code POST /api/schedules/{id}/trigger} (brief §3 INV-10 — manual trigger
 * bypasses the {@code enabled} flag).
 *
 * <p>BE-2's executor listens and runs the task asynchronously. The REST endpoint
 * returns immediately after publishing — the caller polls run history if it
 * wants completion status.
 */
public record ScheduledTaskTriggerRequestedEvent(Long taskId) {
}
