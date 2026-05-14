package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * PROD-LABEL-CLUSTER V1: one row per (session × annotation_type × annotation_value × source).
 *
 * <p>UNIQUE constraint {@code uq_session_annotation} backs idempotent upserts —
 * re-running the hourly signal-stage detection on the same window does not produce
 * duplicates. {@link #source} distinguishes deterministic signal-derived rows from
 * LLM-judged rows (and V3 human corrections).
 *
 * <p>{@link #sessionId} is the t_session.id (VARCHAR 36) but is intentionally NOT
 * an FK at the schema level — see tech-design.md §2.1.
 */
@Entity
@Table(
        name = "t_session_annotation",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_session_annotation",
                columnNames = {"session_id", "annotation_type", "annotation_value", "source"}
        )
)
public class SessionAnnotationEntity {

    public static final String SOURCE_SIGNAL = "signal";
    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_HUMAN = "human";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "annotation_type", nullable = false, length = 32)
    private String annotationType;

    @Column(name = "annotation_value", nullable = false, length = 64)
    private String annotationValue;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence = new BigDecimal("1.00");

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SessionAnnotationEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAnnotationType() { return annotationType; }
    public void setAnnotationType(String annotationType) { this.annotationType = annotationType; }

    public String getAnnotationValue() { return annotationValue; }
    public void setAnnotationValue(String annotationValue) { this.annotationValue = annotationValue; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
