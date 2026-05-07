package com.skillforge.server.dto;

import com.skillforge.server.entity.ScheduledTaskRunEntity;

import java.time.Instant;

public record ScheduledTaskRunResponse(
        Long id,
        Long taskId,
        Instant triggeredAt,
        Instant finishedAt,
        String status,
        String errorMessage,
        String triggeredSessionId,
        boolean manual
) {
    public static ScheduledTaskRunResponse from(ScheduledTaskRunEntity e) {
        return new ScheduledTaskRunResponse(
                e.getId(),
                e.getTaskId(),
                e.getTriggeredAt(),
                e.getFinishedAt(),
                e.getStatus(),
                e.getErrorMessage(),
                e.getTriggeredSessionId(),
                e.isManual()
        );
    }
}
