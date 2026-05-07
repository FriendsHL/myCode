package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * P12 scheduled-task execution history. One row per fire (or skip).
 *
 * <p>Status state machine:
 * <ul>
 *   <li>{@code running}  — fire began, session running</li>
 *   <li>{@code success}  — session completed normally</li>
 *   <li>{@code failure}  — session terminated with error</li>
 *   <li>{@code skipped}  — INV-4 skip-if-running blocked the fire</li>
 *   <li>{@code timeout}  — exceeded execution timeout</li>
 *   <li>{@code paused}   — session reached waiting_user (ask_user) — see brief §6</li>
 * </ul>
 */
@Entity
@Table(name = "t_scheduled_task_run")
public class ScheduledTaskRunEntity {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_TIMEOUT = "timeout";
    public static final String STATUS_PAUSED = "paused";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "triggered_session_id", length = 64)
    private String triggeredSessionId;

    @Column(nullable = false)
    private boolean manual = false;

    public ScheduledTaskRunEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getTriggeredSessionId() {
        return triggeredSessionId;
    }

    public void setTriggeredSessionId(String triggeredSessionId) {
        this.triggeredSessionId = triggeredSessionId;
    }

    public boolean isManual() {
        return manual;
    }

    public void setManual(boolean manual) {
        this.manual = manual;
    }
}
