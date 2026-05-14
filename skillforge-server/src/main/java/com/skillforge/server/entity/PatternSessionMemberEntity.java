package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * PROD-LABEL-CLUSTER V1: membership of a session in a pattern cluster.
 *
 * <p>Composite primary key {@code (pattern_id, session_id)} via
 * {@link PatternSessionMemberId}. Schema-level {@code ON DELETE CASCADE} from
 * {@code t_pattern_session_member.pattern_id} to {@code t_session_pattern.id}
 * means deleting a pattern purges all its membership rows automatically (V74).
 *
 * <p>Why {@code @IdClass} over {@code @EmbeddedId}: simpler with two primitive-shape
 * keys. Choice mirrors what {@code SessionMessageEntity}-style projects in
 * the broader Spring Boot ecosystem typically do for relation tables. Project
 * has no pre-existing composite-key style precedent — going with the leaner option.
 */
@Entity
@Table(name = "t_pattern_session_member")
@IdClass(PatternSessionMemberId.class)
public class PatternSessionMemberEntity {

    @Id
    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    @Id
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    public PatternSessionMemberEntity() {}

    public Long getPatternId() { return patternId; }
    public void setPatternId(Long patternId) { this.patternId = patternId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant addedAt) { this.addedAt = addedAt; }
}
