package com.skillforge.server.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link PatternSessionMemberEntity}: (patternId, sessionId).
 *
 * <p>Used with JPA {@code @IdClass}. Required to be {@link Serializable} with
 * {@code equals}/{@code hashCode} matching the entity's two {@code @Id} fields.
 */
public class PatternSessionMemberId implements Serializable {

    private Long patternId;
    private String sessionId;

    public PatternSessionMemberId() {}

    public PatternSessionMemberId(Long patternId, String sessionId) {
        this.patternId = patternId;
        this.sessionId = sessionId;
    }

    public Long getPatternId() { return patternId; }
    public void setPatternId(Long patternId) { this.patternId = patternId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatternSessionMemberId other)) return false;
        return Objects.equals(patternId, other.patternId)
                && Objects.equals(sessionId, other.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patternId, sessionId);
    }
}
