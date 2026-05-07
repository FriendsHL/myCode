package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * P12 user-type scheduled task.
 *
 * <p>Either {@link #cronExpr} (recurring) or {@link #oneShotAt} (single-fire) must be set,
 * never both — enforced by DB CHECK {@code chk_st_trigger_xor} (V59) and re-checked in
 * {@code ScheduledTaskService} so misuse fails before hitting the DB.
 *
 * <p>{@link #channelTarget} is stored as JSON-encoded TEXT (project convention — see
 * {@code EvalScenarioEntity} comment). Service layer owns parse/serialize via the
 * Spring-managed {@code ObjectMapper}.
 */
@Entity
@Table(name = "t_scheduled_task")
@EntityListeners(AuditingEntityListener.class)
public class ScheduledTaskEntity {

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    public static final String SESSION_MODE_NEW = "new";
    public static final String SESSION_MODE_REUSE = "reuse";

    public static final String CONCURRENCY_SKIP_IF_RUNNING = "skip-if-running";

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "creator_user_id", nullable = false)
    private Long creatorUserId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "cron_expr", length = 64)
    private String cronExpr;

    @Column(name = "one_shot_at")
    private Instant oneShotAt;

    @Column(nullable = false, length = 64)
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "prompt_template", nullable = false, columnDefinition = "TEXT")
    private String promptTemplate;

    @Column(name = "session_mode", nullable = false, length = 16)
    private String sessionMode = SESSION_MODE_NEW;

    @Column(name = "reused_session_id", length = 64)
    private String reusedSessionId;

    /** JSON: {"channel_type":"feishu","channel_id":"oc_xxx"} — null = no channel push. */
    @Column(name = "channel_target", columnDefinition = "TEXT")
    private String channelTarget;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "concurrency_policy", nullable = false, length = 16)
    private String concurrencyPolicy = CONCURRENCY_SKIP_IF_RUNNING;

    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    @Column(name = "last_fire_at")
    private Instant lastFireAt;

    @Column(nullable = false, length = 16)
    private String status = STATUS_IDLE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ScheduledTaskEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(Long creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getCronExpr() {
        return cronExpr;
    }

    public void setCronExpr(String cronExpr) {
        this.cronExpr = cronExpr;
    }

    public Instant getOneShotAt() {
        return oneShotAt;
    }

    public void setOneShotAt(Instant oneShotAt) {
        this.oneShotAt = oneShotAt;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public String getSessionMode() {
        return sessionMode;
    }

    public void setSessionMode(String sessionMode) {
        this.sessionMode = sessionMode;
    }

    public String getReusedSessionId() {
        return reusedSessionId;
    }

    public void setReusedSessionId(String reusedSessionId) {
        this.reusedSessionId = reusedSessionId;
    }

    public String getChannelTarget() {
        return channelTarget;
    }

    public void setChannelTarget(String channelTarget) {
        this.channelTarget = channelTarget;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConcurrencyPolicy() {
        return concurrencyPolicy;
    }

    public void setConcurrencyPolicy(String concurrencyPolicy) {
        this.concurrencyPolicy = concurrencyPolicy;
    }

    public Instant getNextFireAt() {
        return nextFireAt;
    }

    public void setNextFireAt(Instant nextFireAt) {
        this.nextFireAt = nextFireAt;
    }

    public Instant getLastFireAt() {
        return lastFireAt;
    }

    public void setLastFireAt(Instant lastFireAt) {
        this.lastFireAt = lastFireAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
