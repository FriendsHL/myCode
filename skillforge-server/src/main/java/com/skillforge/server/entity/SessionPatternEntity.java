package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * PROD-LABEL-CLUSTER V1: a materialised cluster of sessions sharing a failure
 * signature (outcome × suspect_surface × top_failing_tool × agent_id).
 *
 * <p>{@link #signature} is UNIQUE so cluster re-computation is idempotent —
 * see tech-design.md §5.3.
 *
 * <p>{@link #suggestedSurface} is reserved for V3 attribution agent output; in V1
 * it should be set equal to {@link #suspectSurface}.
 */
@Entity
@Table(name = "t_session_pattern")
public class SessionPatternEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256, unique = true)
    private String signature;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "suspect_surface", nullable = false, length = 32)
    private String suspectSurface;

    @Column(name = "top_failing_tool", length = 128)
    private String topFailingTool;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "member_count", nullable = false)
    private int memberCount = 0;

    @Column(name = "suggested_surface", length = 32)
    private String suggestedSurface;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SessionPatternEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getSuspectSurface() { return suspectSurface; }
    public void setSuspectSurface(String suspectSurface) { this.suspectSurface = suspectSurface; }

    public String getTopFailingTool() { return topFailingTool; }
    public void setTopFailingTool(String topFailingTool) { this.topFailingTool = topFailingTool; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }

    public String getSuggestedSurface() { return suggestedSurface; }
    public void setSuggestedSurface(String suggestedSurface) { this.suggestedSurface = suggestedSurface; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
