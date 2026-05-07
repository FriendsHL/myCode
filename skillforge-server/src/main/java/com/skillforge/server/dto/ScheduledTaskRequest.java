package com.skillforge.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Wire DTO for POST /api/schedules and PUT /api/schedules/{id}.
 *
 * <p>All boxed types so PUT can do partial patches: a {@code null} field means
 * "don't touch", except for {@code cronExpr} / {@code oneShotAt} where a
 * present-and-null is interpreted as "clear this field" — that's how the
 * cron ↔ one-shot conversion (INV-3) works in one PUT. Service layer uses the
 * {@link #cronExprPresent} / {@link #oneShotAtPresent} flags to distinguish
 * "absent" from "present-as-null".
 *
 * <p>Note: the simplest serialization-friendly way to model "tri-state" patches
 * here is the {@code Optional<T>}-shaped pattern with explicit "field present"
 * flags. We keep it simple — the controller hands the request to
 * {@code ScheduledTaskService} which checks both flags as documented in
 * {@code applyPatch}.
 *
 * <p>{@code channelTarget} is a nested object on the wire ({@code channelType} +
 * {@code channelId} camelCase keys, matching FE {@code ChannelTarget}). Service layer
 * serializes the map to a canonical JSON String for the entity TEXT column. Same
 * pattern as {@code EvalController.toScenarioEntityMap} — keep wire types nested,
 * persist as JSON-encoded TEXT.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduledTaskRequest {

    private String name;
    private Long agentId;
    private String cronExpr;
    private Instant oneShotAt;
    private String timezone;
    private String promptTemplate;
    private String sessionMode;
    private Map<String, Object> channelTarget;
    private Boolean enabled;

    /** Tri-state guard: true if {@code cronExpr} key was present in the request body (even with null value). */
    private boolean cronExprPresent;
    /** Tri-state guard: true if {@code oneShotAt} key was present in the request body (even with null value). */
    private boolean oneShotAtPresent;
    /** Tri-state guard: true if {@code channelTarget} key was present in the request body (even with null value). */
    private boolean channelTargetPresent;

    public ScheduledTaskRequest() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) {
        this.cronExpr = cronExpr;
        this.cronExprPresent = true;
    }

    public Instant getOneShotAt() { return oneShotAt; }
    public void setOneShotAt(Instant oneShotAt) {
        this.oneShotAt = oneShotAt;
        this.oneShotAtPresent = true;
    }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

    public String getSessionMode() { return sessionMode; }
    public void setSessionMode(String sessionMode) { this.sessionMode = sessionMode; }

    public Map<String, Object> getChannelTarget() { return channelTarget; }
    public void setChannelTarget(Map<String, Object> channelTarget) {
        this.channelTarget = channelTarget;
        this.channelTargetPresent = true;
    }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public boolean isCronExprPresent() { return cronExprPresent; }
    public void markCronExprPresent() { this.cronExprPresent = true; }

    public boolean isOneShotAtPresent() { return oneShotAtPresent; }
    public void markOneShotAtPresent() { this.oneShotAtPresent = true; }

    public boolean isChannelTargetPresent() { return channelTargetPresent; }
    public void markChannelTargetPresent() { this.channelTargetPresent = true; }
}
